package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.backtest.BacktestRequestEnvelope;
import com.idea2strategy.backend.application.competition.RoomEvaluationStartPort;
import com.idea2strategy.backend.application.competition.RoomEvaluationStartReport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomEvaluationStartJooqAdapter implements RoomEvaluationStartPort {
    private static final String ACCOUNT_OPEN_TYPE = "ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED";
    private static final String ACCOUNT_OPEN_SCHEMA = "room-evaluation-account-open-requested.v1";
    private static final String LIVE_EVALUATION_INPUT_VERSION = "live-evaluation-input.v1";
    private static final String INITIAL_STATE_VERSION = "live-evaluation-initial-state.v1";
    private final DSLContext dsl;

    public RoomEvaluationStartJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public RoomEvaluationStartReport startEligible(Instant observedAt, int limit) {
        OffsetDateTime observed = observedAt.atOffset(ZoneOffset.UTC);
        var candidates = dsl.fetch(
                "select p.id as participation_id, p.room_id, p.bot_id, r.competition_type::text, "
                        + "b.lifecycle_status::text as lifecycle_status, b.started_at, b.execution_eligible_from, "
                        + "rr.initial_cash_amount as room_initial_cash, rr.currency_code as room_currency_code, "
                        + "rr.fee_policy_id as room_fee_policy_id, "
                        + "rr.buying_power_buffer_policy_id as room_buffer_policy_id, "
                        + "rr.precision_rules_version as room_precision_rules_version, rr.slippage_rate_bps, "
                        + "rr.rules_hash, rr.scoring_template_version_id, "
                        + "rr.scoring_parameters::text as scoring_parameters, "
                        + "stv.template_code as scoring_template_code, stv.version as scoring_template_version, "
                        + "stv.rules_hash as scoring_template_rules_hash, "
                        + "fp.policy_code as fee_policy_code, fp.version as fee_policy_version, "
                        + "fp.fee_rate_bps, fp.calculation_rules_version as fee_calculation_rules_version, "
                        + "fp.rules_hash as fee_policy_rules_hash, "
                        + "bp.policy_code as buffer_policy_code, bp.version as buffer_policy_version, "
                        + "bp.buffer_bps, bp.rounding_rules_version as buffer_rounding_rules_version, "
                        + "bp.rules_hash as buffer_policy_rules_hash, "
                        + "rs.evaluation_starts_at, rs.evaluation_ends_at, "
                        + "lc.initial_cash_amount as launch_initial_cash, lc.currency_code, "
                        + "lc.fee_policy_id as launch_fee_policy_id, "
                        + "lc.buying_power_buffer_policy_id as launch_buffer_policy_id, "
                        + "lc.precision_rules_version as launch_precision_rules_version, "
                        + "lc.slippage_rate_bps as launch_slippage_rate_bps, s.snapshot_hash "
                        + "from competition.participations p "
                        + "join competition.rooms r on r.id = p.room_id "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "join competition.scoring_template_versions stv on stv.id = rr.scoring_template_version_id "
                        + "join trading.fee_policy_versions fp on fp.id = rr.fee_policy_id "
                        + "join trading.buying_power_buffer_policy_versions bp "
                        + "on bp.id = rr.buying_power_buffer_policy_id "
                        + "join bot.bots b on b.id = p.bot_id "
                        + "join bot.launch_configurations lc on lc.bot_id = b.id "
                        + "join bot.launch_snapshots s on s.bot_id = b.id "
                        + "where r.status = 'EVALUATING'::competition.room_status "
                        + "and p.status = 'REGISTERED'::competition.participation_status "
                        + "and p.evaluation_started_at is null "
                        + "and rs.evaluation_starts_at <= ?::timestamptz "
                        + "and b.execution_eligible_from <= ?::timestamptz "
                        + "order by p.joined_at, p.id limit ? for update of p, b skip locked",
                observed, observed, limit);

        for (var candidate : candidates) {
            start(candidate, observed);
        }
        return new RoomEvaluationStartReport(observedAt, candidates.size());
    }

    private void start(Record candidate, OffsetDateTime observedAt) {
        UUID participationId = candidate.get("participation_id", UUID.class);
        UUID roomId = candidate.get("room_id", UUID.class);
        UUID botId = candidate.get("bot_id", UUID.class);
        validateCandidate(candidate, botId);
        boolean live = "LIVE_PAPER".equals(candidate.get("competition_type", String.class));
        String liveEvaluationInputHash = null;
        if (live) {
            liveEvaluationInputHash = liveEvaluationInputHash(candidate, roomId);
            validateRoomInputHash(roomId, liveEvaluationInputHash);
        }

        UUID evaluationSegmentId;
        if (live) {
            evaluationSegmentId = insertLiveEvaluationSegment(
                    candidate, participationId);
        } else {
            evaluationSegmentId = derivedId("backtest-evaluation-segment.v1", participationId);
        }

        BigDecimal initialCash = candidate.get("room_initial_cash", BigDecimal.class);
        dsl.execute(
                "update competition.participations "
                        + "set status = 'PENDING_LEDGER'::competition.participation_status where id = ?",
                participationId);

        int participationEventSequence = nextParticipationEventSequence(participationId);
        if (live) {
            dsl.execute(
                    "insert into competition.participation_events "
                            + "(id, participation_id, event_sequence, event_type, occurred_at, payload_document) "
                            + "values (?, ?, ?, 'LEDGER_PENDING', ?::timestamptz, "
                            + "jsonb_build_object('roomId', ?::text, 'botId', ?::text, "
                            + "'initialCashAmount', ?::text, 'liveEvaluationInputVersion', ?, "
                            + "'liveEvaluationInputHash', ?))",
                    derivedId("participation-event", participationId), participationId, participationEventSequence,
                    observedAt, roomId, botId, initialCash.toPlainString(),
                    LIVE_EVALUATION_INPUT_VERSION, liveEvaluationInputHash);
        } else {
            dsl.execute(
                    "insert into competition.participation_events "
                            + "(id, participation_id, event_sequence, event_type, occurred_at, payload_document) "
                            + "values (?, ?, ?, 'LEDGER_PENDING', ?::timestamptz, "
                            + "jsonb_build_object('roomId', ?::text, 'botId', ?::text, 'initialCashAmount', ?::text))",
                    derivedId("participation-event", participationId), participationId, participationEventSequence,
                    observedAt, roomId, botId, initialCash.toPlainString());
        }
        insertAccountOpenRequest(
                candidate, participationId, roomId, botId, evaluationSegmentId,
                observedAt);
    }

    /**
     * Emits the durable competition-lane request when the room has its locked backtest plan.
     * Older fixture/legacy rooms without that plan keep the existing start command but cannot be
     * presented as backtest-engine ready.
     */
    private void insertCompetitionBacktestRequest(
            UUID roomId, UUID participationId, UUID botId, OffsetDateTime observedAt) {
        var context = dsl.fetchOne(
                "select ep.plan_version, ep.plan_hash, ep.period_count, s.snapshot_hash, lp.plan_checksum, "
                        + "lc.accounting_rules_version, rr.scoring_template_version_id, rr.rules_hash, "
                        + "rr.initial_cash_amount, rr.currency_code "
                        + "from competition.backtest_evaluation_plans ep "
                        + "join competition.room_rules rr on rr.room_id = ep.room_id "
                        + "join bot.launch_snapshots s on s.bot_id = ? "
                        + "join bot.launch_contract_plans lp on lp.bot_id = ? "
                        + "join bot.launch_configurations lc on lc.bot_id = ? where ep.room_id = ?",
                botId, botId, botId, roomId);
        if (context == null) {
            return;
        }
        var request = BacktestRequestEnvelope.competition(
                roomId,
                participationId,
                botId,
                context.get("plan_version", String.class),
                prefixed(context.get("plan_hash", String.class)),
                prefixed(context.get("snapshot_hash", String.class)),
                prefixed(context.get("plan_checksum", String.class)),
                context.get("accounting_rules_version", String.class),
                context.get("scoring_template_version_id", UUID.class),
                prefixed(context.get("rules_hash", String.class)),
                context.get("initial_cash_amount", BigDecimal.class),
                context.get("currency_code", String.class).trim(),
                competitionPeriods(roomId, context.get("period_count", Integer.class)),
                observedAt.toInstant());
        Number sequence = (Number) dsl.fetchValue(
                "select coalesce(max(aggregate_sequence), 0) + 1 from operations.outbox_messages "
                        + "where owner_domain = 'backtest-request' and aggregate_id = ?",
                participationId);
        dsl.execute(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                        + "payload_document, producer_idempotency_key, idempotency_key, created_at) "
                        + "values (?, 'backtest-request', ?, ?, ?, ?, ?::jsonb, ?, ?, ?::timestamptz) "
                        + "on conflict (idempotency_key) do nothing",
                request.messageId(), participationId, sequence.longValue(), request.eventType(),
                request.eventSchemaVersion(), request.payloadDocument(), request.producerIdempotencyKey(),
                request.producerIdempotencyKey(), observedAt);
    }

    private List<BacktestRequestEnvelope.CompetitionPeriod> competitionPeriods(UUID roomId, int expectedCount) {
        var periodRows = dsl.fetch(
                "select id, period_sequence, evaluation_start, evaluation_end, importance_weight, input_set_hash "
                        + "from competition.backtest_evaluation_periods where evaluation_plan_room_id = ? "
                        + "order by period_sequence, id",
                roomId);
        if (periodRows.size() != expectedCount) {
            throw new IllegalStateException("Locked competition period count does not match its plan");
        }
        return periodRows.stream().map(period -> {
            UUID periodId = period.get("id", UUID.class);
            LocalDate start = period.get("evaluation_start", LocalDate.class);
            LocalDate end = period.get("evaluation_end", LocalDate.class);
            var datasets = dsl.fetch(
                            "select pd.dataset_manifest_id, pd.purpose_code, pd.locked_dataset_hash, "
                                    + "dm.dataset_hash, dm.status::text as dataset_status, dm.available_at, "
                                    + "dm.period_start::date as dataset_start, dm.period_end::date as dataset_end "
                                    + "from competition.backtest_period_datasets pd "
                                    + "join market_data.dataset_manifests dm on dm.id = pd.dataset_manifest_id "
                                    + "where pd.evaluation_period_id = ? order by pd.purpose_code, pd.dataset_manifest_id",
                            periodId)
                    .map(dataset -> {
                        String locked = prefixed(dataset.get("locked_dataset_hash", String.class));
                        String actual = prefixed(dataset.get("dataset_hash", String.class));
                        if (!locked.equals(actual)
                                || !"AVAILABLE".equals(dataset.get("dataset_status", String.class))
                                || dataset.get("available_at", OffsetDateTime.class) == null
                                || dataset.get("dataset_start", LocalDate.class).isAfter(start)
                                || dataset.get("dataset_end", LocalDate.class).isBefore(end)) {
                            throw new IllegalStateException(
                                    "Locked competition dataset is unavailable, changed, or does not cover its period");
                        }
                        return new BacktestRequestEnvelope.CompetitionDataset(
                                dataset.get("dataset_manifest_id", UUID.class),
                                dataset.get("purpose_code", String.class), locked);
                    });
            var features = dsl.fetch(
                            "select pf.feature_materialization_id, pf.locked_result_hash, fm.result_hash, "
                                    + "fm.status::text as materialization_status, fm.available_at "
                                    + "from competition.backtest_period_feature_materializations pf "
                                    + "join market_data.feature_materializations fm "
                                    + "on fm.id = pf.feature_materialization_id "
                                    + "where pf.evaluation_period_id = ? order by pf.feature_materialization_id",
                            periodId)
                    .map(feature -> {
                        String locked = prefixed(feature.get("locked_result_hash", String.class));
                        String actual = prefixed(feature.get("result_hash", String.class));
                        if (!locked.equals(actual)
                                || !"SUCCEEDED".equals(feature.get("materialization_status", String.class))
                                || feature.get("available_at", OffsetDateTime.class) == null) {
                            throw new IllegalStateException(
                                    "Locked competition feature materialization is unavailable or changed");
                        }
                        return new BacktestRequestEnvelope.CompetitionFeatureMaterialization(
                                feature.get("feature_materialization_id", UUID.class), locked);
                    });
            return new BacktestRequestEnvelope.CompetitionPeriod(
                    periodId,
                    period.get("period_sequence", Integer.class),
                    start,
                    end,
                    period.get("importance_weight", BigDecimal.class),
                    prefixed(period.get("input_set_hash", String.class)),
                    datasets,
                    features);
        }).toList();
    }

    private static String prefixed(String value) {
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

    private UUID insertLiveEvaluationSegment(Record candidate, UUID participationId) {
        UUID segmentId = derivedId("live-evaluation-segment.v1", participationId);
        OffsetDateTime startsAt = candidate.get("evaluation_starts_at", OffsetDateTime.class);
        OffsetDateTime endsAt = candidate.get("evaluation_ends_at", OffsetDateTime.class);
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalStateException("Live evaluation segment must have a non-empty schedule window");
        }
        dsl.execute(
                "insert into competition.live_evaluation_segments "
                        + "(id, participation_id, segment_type, starts_at, ends_at, start_event_sequence, "
                        + "initial_state_hash) values (?, ?, 'OFFICIAL_EVALUATION', ?::timestamptz, "
                        + "?::timestamptz, null, null)",
                segmentId, participationId, startsAt, endsAt);
        return segmentId;
    }

    private void validateCandidate(Record candidate, UUID botId) {
        if (!"RUNNING".equals(candidate.get("lifecycle_status", String.class))
                || candidate.get("started_at", OffsetDateTime.class) != null) {
            throw new IllegalStateException("Room evaluation bot is not in a startable lifecycle state");
        }
        if (!candidate.get("room_initial_cash", BigDecimal.class)
                        .equals(candidate.get("launch_initial_cash", BigDecimal.class))
                || !candidate.get("room_currency_code", String.class).trim()
                        .equals(candidate.get("currency_code", String.class).trim())
                || !candidate.get("room_fee_policy_id", UUID.class)
                        .equals(candidate.get("launch_fee_policy_id", UUID.class))
                || !candidate.get("room_buffer_policy_id", UUID.class)
                        .equals(candidate.get("launch_buffer_policy_id", UUID.class))
                || !candidate.get("room_precision_rules_version", String.class)
                        .equals(candidate.get("launch_precision_rules_version", String.class))
                || !candidate.get("slippage_rate_bps", Integer.class)
                        .equals(candidate.get("launch_slippage_rate_bps", Integer.class))) {
            throw new IllegalStateException("Room evaluation launch configuration does not match locked room rules");
        }
        Number stateRows = (Number) dsl.fetchValue(
                "select "
                        + "(select count(*) from bot.runtime_state_values where bot_id = ?) + "
                        + "(select count(*) from bot.evaluation_runs where bot_id = ?) + "
                        + "(select count(*) from trading.order_intents where bot_id = ?) + "
                        + "(select count(*) from trading.orders where bot_id = ?) + "
                        + "(select count(*) from trading.order_groups where bot_id = ?) + "
                        + "(select count(*) from trading.fills where bot_id = ?) + "
                        + "(select count(*) from trading.resource_reservations where bot_id = ?) + "
                        + "(select count(*) from trading.position_lots where bot_id = ?) + "
                        + "(select count(*) from trading.flow_position_projections where bot_id = ?) + "
                        + "(select count(*) from trading.ledger_accounts where bot_id = ?) + "
                        + "(select count(*) from trading.ledger_transactions where bot_id = ?)",
                botId, botId, botId, botId, botId, botId, botId, botId, botId, botId, botId);
        if (stateRows.longValue() != 0) {
            throw new IllegalStateException("Room evaluation official state is not empty");
        }
    }

    private void insertAccountOpenRequest(
            Record candidate,
            UUID participationId,
            UUID roomId,
            UUID botId,
            UUID evaluationSegmentId,
            OffsetDateTime observedAt) {
        Number nextSequence = (Number) dsl.fetchValue(
                "select coalesce(max(aggregate_sequence), 0) + 1 from operations.outbox_messages "
                        + "where owner_domain = 'room-performance' and aggregate_id = ?",
                participationId);
        UUID commandId = derivedId("room-evaluation-account-open-command.v1", participationId);
        UUID messageId = derivedId("room-evaluation-account-open-message.v1", participationId);
        OffsetDateTime effectiveAt = candidate.get("execution_eligible_from", OffsetDateTime.class);
        String producerKey = "room-evaluation-account-open:" + participationId;
        dsl.execute(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                        + "payload_document, producer_idempotency_key, idempotency_key, created_at) "
                        + "values (?, 'room-performance', ?, ?, ?, ?, "
                        + "jsonb_build_object("
                        + "'commandId', ?::text, 'messageId', ?::text, 'producerIdempotencyKey', ?, "
                        + "'roomId', ?::text, 'participationId', ?::text, 'botId', ?::text, "
                        + "'evaluationSegmentId', ?::text, 'initialCash', ?::text, 'currency', ?, "
                        + "'feePolicyVersionId', ?::text, 'buyingPowerPolicyVersionId', ?::text, "
                        + "'effectiveAt', ?::text), ?, ?, ?::timestamptz) "
                        + "on conflict (idempotency_key) do nothing",
                messageId, participationId, nextSequence.longValue(), ACCOUNT_OPEN_TYPE, ACCOUNT_OPEN_SCHEMA,
                commandId, messageId, producerKey, roomId, participationId, botId, evaluationSegmentId,
                candidate.get("room_initial_cash", BigDecimal.class).toPlainString(),
                candidate.get("room_currency_code", String.class).trim(),
                candidate.get("room_fee_policy_id", UUID.class),
                candidate.get("room_buffer_policy_id", UUID.class), effectiveAt.toInstant().toString(),
                producerKey, producerKey, observedAt);
    }

    private String liveEvaluationInputHash(Record candidate, UUID roomId) {
        if (!"LIVE_PAPER".equals(candidate.get("competition_type", String.class))) {
            throw new IllegalStateException("Official live evaluation input requires a LIVE_PAPER room");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestField(digest, "inputVersion", LIVE_EVALUATION_INPUT_VERSION);
            digestField(digest, "roomId", roomId.toString());
            digestField(digest, "evaluationStartsAt",
                    candidate.get("evaluation_starts_at", OffsetDateTime.class).toInstant().toString());
            digestField(digest, "evaluationEndsAt",
                    candidate.get("evaluation_ends_at", OffsetDateTime.class).toInstant().toString());
            digestField(digest, "currencyCode", candidate.get("room_currency_code", String.class).trim());
            digestField(digest, "initialCashAmount",
                    candidate.get("room_initial_cash", BigDecimal.class).toPlainString());
            digestField(digest, "feePolicyId", candidate.get("room_fee_policy_id", UUID.class).toString());
            digestField(digest, "feePolicyCode", candidate.get("fee_policy_code", String.class));
            digestField(digest, "feePolicyVersion", candidate.get("fee_policy_version", String.class));
            digestField(digest, "feeRateBps", candidate.get("fee_rate_bps", Integer.class).toString());
            digestField(digest, "feeCalculationRulesVersion",
                    candidate.get("fee_calculation_rules_version", String.class));
            digestField(digest, "feePolicyRulesHash", candidate.get("fee_policy_rules_hash", String.class));
            digestField(digest, "slippageRateBps", candidate.get("slippage_rate_bps", Integer.class).toString());
            digestField(digest, "buyingPowerBufferPolicyId",
                    candidate.get("room_buffer_policy_id", UUID.class).toString());
            digestField(digest, "buyingPowerBufferPolicyCode",
                    candidate.get("buffer_policy_code", String.class));
            digestField(digest, "buyingPowerBufferPolicyVersion",
                    candidate.get("buffer_policy_version", String.class));
            digestField(digest, "buyingPowerBufferBps", candidate.get("buffer_bps", Integer.class).toString());
            digestField(digest, "buyingPowerBufferRoundingRulesVersion",
                    candidate.get("buffer_rounding_rules_version", String.class));
            digestField(digest, "buyingPowerBufferRulesHash",
                    candidate.get("buffer_policy_rules_hash", String.class));
            digestField(digest, "precisionRulesVersion",
                    candidate.get("room_precision_rules_version", String.class));
            digestField(digest, "scoringTemplateVersionId",
                    candidate.get("scoring_template_version_id", UUID.class).toString());
            digestField(digest, "scoringTemplateCode", candidate.get("scoring_template_code", String.class));
            digestField(digest, "scoringTemplateVersion", candidate.get("scoring_template_version", String.class));
            digestField(digest, "scoringTemplateRulesHash",
                    candidate.get("scoring_template_rules_hash", String.class));
            digestField(digest, "scoringParameters", candidate.get("scoring_parameters", String.class));
            digestField(digest, "roomRulesHash", candidate.get("rules_hash", String.class));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void validateRoomInputHash(UUID roomId, String inputHash) {
        var hashes = dsl.fetch(
                "select distinct pe.payload_document ->> 'liveEvaluationInputHash' as input_hash "
                        + "from competition.participation_events pe "
                        + "join competition.participations p on p.id = pe.participation_id "
                        + "where p.room_id = ? and pe.event_type = 'LEDGER_PENDING' "
                        + "and pe.payload_document ->> 'liveEvaluationInputHash' is not null",
                roomId);
        if (hashes.stream().anyMatch(record -> !inputHash.equals(record.get("input_hash", String.class)))) {
            throw new IllegalStateException("Locked live evaluation input does not match prior participant evidence");
        }
    }

    private static void digestField(MessageDigest digest, String name, String value) {
        updateLengthPrefixed(digest, name);
        updateLengthPrefixed(digest, value);
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[] {
                (byte) (bytes.length >>> 24),
                (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8),
                (byte) bytes.length
        });
        digest.update(bytes);
    }

    private int nextParticipationEventSequence(UUID participationId) {
        return ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?",
                participationId)).intValue();
    }

    private static UUID derivedId(String kind, UUID participationId) {
        return UUID.nameUUIDFromBytes((kind + ":" + participationId).getBytes(StandardCharsets.UTF_8));
    }
}
