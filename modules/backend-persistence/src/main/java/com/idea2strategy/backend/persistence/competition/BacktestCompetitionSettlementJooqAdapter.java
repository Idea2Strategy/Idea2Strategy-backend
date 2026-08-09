package com.idea2strategy.backend.persistence.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.idea2strategy.backend.application.competition.BacktestCompetitionSettlementPort;
import com.idea2strategy.backend.application.competition.BacktestCompetitionSettlementReport;
import com.idea2strategy.backend.application.competition.OfficialScoringCalculator;
import com.idea2strategy.backend.application.competition.OfficialScoringMetrics;
import com.idea2strategy.backend.application.competition.OfficialScoringRank;
import com.idea2strategy.backend.application.competition.OfficialScoringRanker;
import com.idea2strategy.backend.application.competition.OfficialScoringResult;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.domain.competition.ScoringDirection;
import com.idea2strategy.backend.domain.competition.ScoringTemplateKind;
import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Turns terminal competition-lane runs into immutable BACKTEST aggregates and leaderboards. */
@Repository
public class BacktestCompetitionSettlementJooqAdapter implements BacktestCompetitionSettlementPort {
    private static final int METRIC_SCALE = 8;
    private static final String FAILURE_MISSING_RESULT = "BACKTEST_RESULT_EVIDENCE_MISSING";
    private static final String FAILURE_INVALID_RESULT = "BACKTEST_RESULT_EVIDENCE_INVALID";
    private final DSLContext dsl;
    private final ScoringTemplateCatalogService scoringCatalog;
    private final OfficialScoringCalculator calculator = new OfficialScoringCalculator();
    private final OfficialScoringRanker ranker = new OfficialScoringRanker();
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public BacktestCompetitionSettlementJooqAdapter(
            DSLContext dsl, ScoringTemplateCatalogService scoringCatalog) {
        this.dsl = dsl;
        this.scoringCatalog = scoringCatalog;
    }

    @Override
    @Transactional
    public BacktestCompetitionSettlementReport settleEligible(Instant observedAt, int limit) {
        OffsetDateTime observed = observedAt.atOffset(ZoneOffset.UTC);
        var candidates = dsl.fetch(
                "select p.id as participation_id, p.room_id, ep.period_count, "
                        + "rr.scoring_template_version_id "
                        + "from competition.participations p "
                        + "join competition.rooms r on r.id = p.room_id "
                        + "join competition.backtest_evaluation_plans ep on ep.room_id = r.id "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "where r.competition_type = 'BACKTEST'::competition.competition_type "
                        + "and r.status in ('EVALUATING'::competition.room_status, "
                        + "'ENDED'::competition.room_status) "
                        + "and p.status = 'EVALUATING'::competition.participation_status "
                        + "order by p.evaluation_started_at, p.id limit ? for update of p skip locked",
                limit);

        int completed = 0;
        int failed = 0;
        for (Record candidate : candidates) {
            Settlement settlement;
            try {
                settlement = settleParticipation(candidate, observed);
            } catch (InvalidBacktestResultEvidenceException exception) {
                failParticipation(candidate.get("participation_id", UUID.class), FAILURE_INVALID_RESULT, observed);
                settlement = Settlement.FAILED;
            }
            completed += settlement == Settlement.COMPLETED ? 1 : 0;
            failed += settlement == Settlement.FAILED ? 1 : 0;
        }

        int published = 0;
        int finals = 0;
        for (Record room : leaderboardCandidates()) {
            String status = room.get("status", String.class);
            SnapshotWrite write = writeLeaderboard(room, observed);
            if (write == SnapshotWrite.CREATED) {
                if ("ENDED".equals(status)) {
                    finals++;
                } else {
                    published++;
                }
            }
        }
        return new BacktestCompetitionSettlementReport(
                observedAt, completed, failed, published, finals);
    }

    private Settlement settleParticipation(Record candidate, OffsetDateTime observedAt) {
        UUID participationId = candidate.get("participation_id", UUID.class);
        UUID roomId = candidate.get("room_id", UUID.class);
        int expectedPeriods = candidate.get("period_count", Integer.class);
        var periods = dsl.fetch(
                "select period.id as period_id, period.period_sequence, period.importance_weight, "
                        + "period_run.run_id, run.status::text as run_status, run.failure_code, "
                        + "run.result_hash as run_result_hash, summary.result_hash as summary_result_hash, "
                        + "summary.metrics_document::text as metrics_document, "
                        + "summary.calculation_rules_version "
                        + "from competition.backtest_evaluation_periods period "
                        + "left join competition.backtest_period_runs period_run "
                        + "on period_run.evaluation_period_id = period.id "
                        + "and period_run.participation_id = ? "
                        + "left join backtest.runs run on run.id = period_run.run_id "
                        + "left join backtest.performance_summaries summary on summary.run_id = run.id "
                        + "where period.evaluation_plan_room_id = ? "
                        + "order by period.period_sequence, period.id",
                participationId, roomId);
        if (periods.size() != expectedPeriods || periods.stream().anyMatch(row -> row.get("run_id") == null)) {
            return Settlement.PENDING;
        }
        for (Record period : periods) {
            String status = period.get("run_status", String.class);
            if (List.of("FAILED", "UNAVAILABLE", "CANCELLED").contains(status)) {
                failParticipation(participationId, failureCode(period), observedAt);
                return Settlement.FAILED;
            }
            if (!"COMPLETED".equals(status)) {
                return Settlement.PENDING;
            }
            String runHash = prefixed(period.get("run_result_hash", String.class));
            String summaryHash = prefixed(period.get("summary_result_hash", String.class));
            if (period.get("metrics_document", String.class) == null
                    || runHash == null || !runHash.equals(summaryHash)) {
                failParticipation(participationId, FAILURE_MISSING_RESULT, observedAt);
                return Settlement.FAILED;
            }
        }

        BigDecimal weightedReturn = BigDecimal.ZERO;
        BigDecimal weightedDrawdown = BigDecimal.ZERO;
        BigDecimal weightedSharpe = BigDecimal.ZERO;
        boolean sharpeAvailable = true;
        BigDecimal worstDrawdown = BigDecimal.ZERO;
        List<Map<String, Object>> periodEvidence = new ArrayList<>();
        for (Record period : periods) {
            JsonNode metrics = json(period.get("metrics_document", String.class));
            BigDecimal weight = period.get("importance_weight", BigDecimal.class);
            BigDecimal totalReturn = decimal(metrics, "totalReturnPct");
            BigDecimal drawdown = decimal(metrics, "maxDrawdownPct").abs();
            BigDecimal sharpe = nullableDecimal(metrics, "sharpe");
            weightedReturn = weightedReturn.add(totalReturn.multiply(weight));
            weightedDrawdown = weightedDrawdown.add(drawdown.multiply(weight));
            worstDrawdown = worstDrawdown.max(drawdown);
            if (sharpe == null) {
                sharpeAvailable = false;
            } else {
                weightedSharpe = weightedSharpe.add(sharpe.multiply(weight));
            }
            String resultHash = prefixed(period.get("run_result_hash", String.class));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("periodId", period.get("period_id", UUID.class).toString());
            evidence.put("periodSequence", period.get("period_sequence", Integer.class));
            evidence.put("importanceWeight", weight.toPlainString());
            evidence.put("resultHash", resultHash);
            evidence.put("totalReturnPct", normalized(totalReturn).toPlainString());
            evidence.put("maxDrawdownPct", normalized(drawdown).toPlainString());
            evidence.put("sharpeRatio", sharpe == null ? null : normalized(sharpe).toPlainString());
            periodEvidence.add(evidence);
        }
        OfficialScoringMetrics scoringMetrics = new OfficialScoringMetrics(
                normalized(weightedReturn), normalized(weightedDrawdown),
                sharpeAvailable ? normalized(weightedSharpe) : null);
        ScoringTemplateVersion template = scoringCatalog
                .select(candidate.get("scoring_template_version_id", UUID.class), Map.of())
                .template();
        BigDecimal score;
        try {
            score = calculator.score(template, scoringMetrics);
        } catch (IllegalArgumentException exception) {
            throw new InvalidBacktestResultEvidenceException("backtest metrics cannot be scored", exception);
        }
        String periodSetHash = hash(Map.of("periods", periodEvidence));
        Map<String, Object> metricsDocument = new LinkedHashMap<>();
        metricsDocument.put("schemaVersion", "backtest-competition-aggregate.v1");
        metricsDocument.put("weightedReturnPct", scoringMetrics.totalReturnPct().toPlainString());
        metricsDocument.put("weightedSharpeRatio",
                scoringMetrics.sharpeRatio() == null ? null : scoringMetrics.sharpeRatio().toPlainString());
        metricsDocument.put("weightedMaxDrawdownPct", scoringMetrics.maxDrawdownPct().toPlainString());
        metricsDocument.put("worstPeriodMaxDrawdownPct", normalized(worstDrawdown).toPlainString());
        metricsDocument.put("periodResultSetHash", periodSetHash);
        String aggregateHash = hash(Map.of(
                "participationId", participationId.toString(),
                "roomId", roomId.toString(),
                "scoringTemplateVersionId", template.id().toString(),
                "metrics", metricsDocument,
                "score", score.toPlainString()));
        for (Record period : periods) {
            dsl.execute(
                    "update competition.backtest_period_runs set verified_at = ?::timestamptz, "
                            + "verification_failure_code = null, locked_result_hash = ? "
                            + "where participation_id = ? and evaluation_period_id = ?",
                    observedAt, prefixed(period.get("run_result_hash", String.class)), participationId,
                    period.get("period_id", UUID.class));
        }
        UUID aggregateId = derivedId("backtest-competition-aggregate.v1", participationId + ":" + aggregateHash);
        dsl.execute(
                "insert into competition.backtest_aggregate_results "
                        + "(id, participation_id, evaluation_plan_room_id, scoring_template_version_id, "
                        + "weighted_return_pct, weighted_sharpe_ratio, weighted_max_drawdown_pct, "
                        + "worst_period_max_drawdown_pct, final_score, metrics_document, period_result_set_hash, "
                        + "calculation_rules_version, aggregate_hash, calculated_at, verified_at, published_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::timestamptz, "
                        + "?::timestamptz, ?::timestamptz) on conflict (participation_id) do nothing",
                aggregateId, participationId, roomId, template.id(), scoringMetrics.totalReturnPct(),
                scoringMetrics.sharpeRatio(), scoringMetrics.maxDrawdownPct(), normalized(worstDrawdown), score,
                json(metricsDocument), periodSetHash, OfficialScoringCalculator.CALCULATION_RULES_VERSION,
                aggregateHash, observedAt, observedAt, observedAt);
        Record stored = dsl.fetchOne(
                "select aggregate_hash from competition.backtest_aggregate_results where participation_id = ?",
                participationId);
        if (stored == null || !aggregateHash.equals(stored.get("aggregate_hash", String.class))) {
            throw new IllegalStateException("participation already has a different backtest aggregate");
        }
        dsl.execute(
                "update competition.participations set status = 'COMPLETED'::competition.participation_status, "
                        + "evaluation_finished_at = ?::timestamptz, evaluation_failure_code = null where id = ?",
                observedAt, participationId);
        appendEvent(participationId, "EVALUATION_COMPLETED", null, observedAt);
        return Settlement.COMPLETED;
    }

    private List<Record> leaderboardCandidates() {
        return dsl.fetch(
                "select r.id as room_id, r.status::text as status, r.access_type::text as access_type, "
                        + "r.creator_account_id, rr.scoring_template_version_id, rs.evaluation_ends_at "
                        + "from competition.rooms r "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "where r.competition_type = 'BACKTEST'::competition.competition_type "
                        + "and r.status in ('EVALUATING'::competition.room_status, 'ENDED'::competition.room_status) "
                        + "and not exists (select 1 from competition.room_events event "
                        + "where event.room_id = r.id and event.reason_code = 'INSUFFICIENT_PARTICIPATION') "
                        + "and ((r.status = 'EVALUATING'::competition.room_status and exists "
                        + "(select 1 from competition.backtest_aggregate_results aggregate where "
                        + "aggregate.evaluation_plan_room_id = r.id)) or "
                        + "(r.status = 'ENDED'::competition.room_status and not exists "
                        + "(select 1 from competition.participations p where p.room_id = r.id and p.status not in "
                        + "('COMPLETED'::competition.participation_status, "
                        + "'EVALUATION_FAILED'::competition.participation_status, "
                        + "'WITHDRAWN'::competition.participation_status, "
                        + "'EXPELLED'::competition.participation_status)))) "
                        + "order by r.id for update of r skip locked");
    }

    private SnapshotWrite writeLeaderboard(Record room, OffsetDateTime observedAt) {
        UUID roomId = room.get("room_id", UUID.class);
        String roomStatus = room.get("status", String.class);
        String leaderboardStatus = "ENDED".equals(roomStatus) ? "FINAL" : "PUBLISHED";
        ScoringTemplateVersion template = scoringCatalog
                .select(room.get("scoring_template_version_id", UUID.class), Map.of())
                .template();
        var aggregates = dsl.fetch(
                "select aggregate.id as aggregate_id, aggregate.participation_id, "
                        + "aggregate.weighted_return_pct, aggregate.weighted_sharpe_ratio, "
                        + "aggregate.weighted_max_drawdown_pct, aggregate.final_score, aggregate.aggregate_hash "
                        + "from competition.backtest_aggregate_results aggregate "
                        + "join competition.participations p on p.id = aggregate.participation_id "
                        + "where aggregate.evaluation_plan_room_id = ? and p.status = "
                        + "'COMPLETED'::competition.participation_status order by aggregate.participation_id",
                roomId);
        List<OfficialScoringResult> scoreable = aggregates.map(row -> new OfficialScoringResult(
                row.get("participation_id", UUID.class), row.get("final_score", BigDecimal.class),
                direction(template), new OfficialScoringMetrics(
                        row.get("weighted_return_pct", BigDecimal.class),
                        row.get("weighted_max_drawdown_pct", BigDecimal.class),
                        row.get("weighted_sharpe_ratio", BigDecimal.class))));
        List<OfficialScoringRank> ranked = ranker.rank(scoreable);
        Map<Integer, Long> counts = ranked.stream().collect(java.util.stream.Collectors.groupingBy(
                OfficialScoringRank::rank, java.util.stream.Collectors.counting()));
        Map<UUID, Record> byParticipation = new LinkedHashMap<>();
        aggregates.forEach(row -> byParticipation.put(row.get("participation_id", UUID.class), row));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (OfficialScoringRank rank : ranked) {
            Record aggregate = byParticipation.get(rank.result().participationId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("participationId", rank.result().participationId().toString());
            entry.put("aggregateId", aggregate.get("aggregate_id", UUID.class).toString());
            entry.put("aggregateHash", aggregate.get("aggregate_hash", String.class));
            entry.put("rank", rank.rank());
            entry.put("jointRank", counts.get(rank.rank()) > 1);
            entry.put("score", rank.result().score().toPlainString());
            entries.add(entry);
        }
        String resultHash = hash(Map.of(
                "schemaVersion", "backtest-competition-leaderboard.v1",
                "roomId", roomId.toString(),
                "status", leaderboardStatus,
                "scoringTemplateVersionId", template.id().toString(),
                "entries", entries));
        if (Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from competition.leaderboard_snapshots "
                        + "where room_id = ? and status = ?::competition.leaderboard_status and result_hash = ?)",
                roomId, leaderboardStatus, resultHash))) {
            return SnapshotWrite.UNCHANGED;
        }
        OffsetDateTime cutoff = "FINAL".equals(leaderboardStatus)
                ? room.get("evaluation_ends_at", OffsetDateTime.class) : observedAt;
        UUID snapshotId = derivedId(
                "backtest-competition-leaderboard.v1", roomId + ":" + leaderboardStatus + ":" + resultHash);
        dsl.execute(
                "insert into competition.leaderboard_snapshots "
                        + "(id, room_id, scoring_template_version_id, cutoff_at, status, result_hash, created_at) "
                        + "values (?, ?, ?, ?::timestamptz, ?::competition.leaderboard_status, ?, ?::timestamptz)",
                snapshotId, roomId, template.id(), cutoff, leaderboardStatus, resultHash, observedAt);
        for (Map<String, Object> entry : entries) {
            UUID participationId = UUID.fromString(entry.get("participationId").toString());
            UUID aggregateId = UUID.fromString(entry.get("aggregateId").toString());
            dsl.execute(
                    "insert into competition.leaderboard_entries "
                            + "(snapshot_id, participation_id, backtest_aggregate_result_id, rank, is_joint_rank, "
                            + "eligibility_status, score, tie_break_document, calculation_document) "
                            + "values (?, ?, ?, ?, ?, 'ELIGIBLE', ?, ?::jsonb, ?::jsonb)",
                    snapshotId, participationId, aggregateId, entry.get("rank"), entry.get("jointRank"),
                    new BigDecimal(entry.get("score").toString()),
                    json(Map.of("aggregateHash", entry.get("aggregateHash"))),
                    json(Map.of(
                            "schemaVersion", "backtest-competition-leaderboard-entry.v1",
                            "aggregateHash", entry.get("aggregateHash"))));
        }
        if ("FINAL".equals(leaderboardStatus) && "SECRET".equals(room.get("access_type", String.class))) {
            freezeSecretAccess(roomId, snapshotId, observedAt, room.get("creator_account_id", UUID.class));
        }
        return SnapshotWrite.CREATED;
    }

    private void freezeSecretAccess(
            UUID roomId, UUID snapshotId, OffsetDateTime observedAt, UUID creatorAccountId) {
        Map<UUID, String> grants = new LinkedHashMap<>();
        if (creatorAccountId != null) {
            grants.put(creatorAccountId, "CREATOR");
        }
        dsl.fetch("select distinct owner_account_id from competition.participations where room_id = ? "
                        + "and status not in ('WITHDRAWN'::competition.participation_status, "
                        + "'EXPELLED'::competition.participation_status)", roomId)
                .forEach(row -> grants.putIfAbsent(row.get("owner_account_id", UUID.class), "ACTIVE_PARTICIPANT"));
        for (var grant : grants.entrySet()) {
            dsl.execute(
                    "insert into competition.room_final_access_grants "
                            + "(room_id, account_id, snapshot_id, eligibility_basis, granted_at) "
                            + "values (?, ?, ?, ?, ?::timestamptz) on conflict (room_id, account_id) do nothing",
                    roomId, grant.getKey(), snapshotId, grant.getValue(), observedAt);
        }
    }

    private void failParticipation(UUID participationId, String reason, OffsetDateTime observedAt) {
        dsl.execute(
                "update competition.participations set status = "
                        + "'EVALUATION_FAILED'::competition.participation_status, "
                        + "evaluation_finished_at = ?::timestamptz, evaluation_failure_code = ? where id = ?",
                observedAt, bounded(reason), participationId);
        appendEvent(participationId, "EVALUATION_FAILED", bounded(reason), observedAt);
    }

    private void appendEvent(
            UUID participationId, String eventType, String reason, OffsetDateTime observedAt) {
        int sequence = ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?", participationId)).intValue();
        String material = participationId + ":" + eventType + ":" + sequence;
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, reason_code, occurred_at, "
                        + "payload_document) values (?, ?, ?, ?, ?, ?::timestamptz, "
                        + "jsonb_build_object('reasonCode', ?::text))",
                derivedId("backtest-competition-participation-event.v1", material), participationId,
                sequence, eventType, reason, observedAt, reason);
    }

    private ScoringDirection direction(ScoringTemplateVersion template) {
        return template.kind() == ScoringTemplateKind.SINGLE
                ? template.components().getFirst().direction() : ScoringDirection.HIGHER_IS_BETTER;
    }

    private String failureCode(Record period) {
        String code = period.get("failure_code", String.class);
        return code == null || code.isBlank() ? "BACKTEST_RUN_" + period.get("run_status", String.class) : code;
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new InvalidBacktestResultEvidenceException("backtest metrics are not valid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("settlement evidence is not JSON serializable", exception);
        }
    }

    private String hash(Object value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new InvalidBacktestResultEvidenceException("backtest metric is missing: " + field);
        }
        return value.decimalValue();
    }

    private static BigDecimal nullableDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : decimal(node, field);
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value.setScale(METRIC_SCALE, RoundingMode.HALF_EVEN);
    }

    private static String prefixed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

    private static String bounded(String value) {
        return value.substring(0, Math.min(80, value.length()));
    }

    private static UUID derivedId(String kind, String material) {
        return UUID.nameUUIDFromBytes((kind + ":" + material).getBytes(StandardCharsets.UTF_8));
    }

    private enum Settlement { PENDING, COMPLETED, FAILED }
    private enum SnapshotWrite { UNCHANGED, CREATED }

    private static final class InvalidBacktestResultEvidenceException extends RuntimeException {
        private InvalidBacktestResultEvidenceException(String message) {
            super(message);
        }

        private InvalidBacktestResultEvidenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
