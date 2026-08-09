package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.BacktestEvaluationPlanDefinition;
import com.idea2strategy.backend.application.competition.OfficialBacktestRoomCommandPort;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Transactional;

/** Atomically locks an official BACKTEST room and every hidden evaluation input. */
public class OfficialBacktestRoomJooqCommandAdapter implements OfficialBacktestRoomCommandPort {
    private final DSLContext dsl;

    public OfficialBacktestRoomJooqCommandAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public void save(CompetitionRoom room, BacktestEvaluationPlanDefinition plan) {
        OffsetDateTime createdAt = room.createdAt().atOffset(ZoneOffset.UTC);
        int policyCount = dsl.fetch(
                "select version from backtest.execution_policy_versions "
                        + "where policy_document ->> 'competitionPlanHash' = ? "
                        + "and locked_at <= ?::timestamptz and retired_at is null",
                plan.planHash(), createdAt).size();
        if (policyCount != 1) {
            throw new IllegalArgumentException(
                    "planHash must resolve to exactly one locked competition execution policy");
        }
        for (var period : plan.periods()) {
            validatePeriodInputs(period, createdAt);
        }

        dsl.execute(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, created_by_operator_id, name, access_type, "
                        + "status, created_at) values (?, 'BACKTEST', 'PLATFORM', ?, ?, "
                        + "?::competition.room_access_type, 'DRAFT', ?::timestamptz)",
                room.id(), room.createdByOperatorId(), room.name(), room.accessType().name(), createdAt);
        dsl.execute(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, currency_code, "
                        + "bot_participation_limit, per_account_bot_limit, eligibility_document, "
                        + "market_scope_document, scoring_parameters, fee_policy_id, slippage_rate_bps, "
                        + "buying_power_buffer_policy_id, precision_rules_version, rules_hash, locked_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::timestamptz)",
                room.id(), room.scoringTemplateVersionId(), room.initialCashAmount(), room.currencyCode(),
                room.botParticipationLimit(), room.perAccountBotLimit(), room.eligibilityDocument(),
                room.marketScopeDocument(), room.scoringParameters(), room.feePolicyId(), room.slippageRateBps(),
                room.buyingPowerBufferPolicyId(), room.precisionRulesVersion(), room.rulesHash(), createdAt);
        dsl.execute(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, "
                        + "?::timestamptz, ?::timestamptz, ?)",
                room.id(), utc(room.schedule().recruitmentOpensAt()), utc(room.schedule().participationOpensAt()),
                utc(room.schedule().evaluationStartsAt()), utc(room.schedule().participationClosesAt()),
                utc(room.schedule().evaluationEndsAt()), utc(room.schedule().finalizationDeadlineAt()),
                room.schedule().timezoneName());
        dsl.execute(
                "insert into competition.backtest_evaluation_plans "
                        + "(room_id, plan_version, period_count, plan_hash, commitment_hash, "
                        + "commitment_nonce_ciphertext, nonce_key_version, locked_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
                room.id(), plan.planVersion(), plan.periods().size(), plan.planHash(), plan.commitmentHash(),
                plan.commitmentNonceCiphertext(), plan.nonceKeyVersion(), createdAt);
        for (var period : plan.periods()) {
            dsl.execute(
                    "insert into competition.backtest_evaluation_periods "
                            + "(id, evaluation_plan_room_id, period_sequence, evaluation_start, evaluation_end, "
                            + "importance_weight, input_set_hash) values (?, ?, ?, ?, ?, ?, ?)",
                    period.id(), room.id(), period.sequence(), period.evaluationStart(), period.evaluationEnd(),
                    period.importanceWeight(), period.inputSetHash());
            for (var dataset : period.datasets()) {
                dsl.execute(
                        "insert into competition.backtest_period_datasets "
                                + "(evaluation_period_id, dataset_manifest_id, purpose_code, locked_dataset_hash) "
                                + "values (?, ?, ?, ?)",
                        period.id(), dataset.manifestId(), dataset.purposeCode(), dataset.lockedDatasetHash());
            }
            for (var feature : period.featureMaterializations()) {
                dsl.execute(
                        "insert into competition.backtest_period_feature_materializations "
                                + "(evaluation_period_id, feature_materialization_id, locked_result_hash) "
                                + "values (?, ?, ?)",
                        period.id(), feature.id(), feature.lockedResultHash());
            }
        }
    }

    private void validatePeriodInputs(
            BacktestEvaluationPlanDefinition.Period period, OffsetDateTime createdAt) {
        for (var dataset : period.datasets()) {
            Record stored = dsl.fetchOne(
                    "select dataset_hash, status::text as status, available_at, "
                            + "period_start::date as period_start, period_end::date as period_end "
                            + "from market_data.dataset_manifests where id = ?",
                    dataset.manifestId());
            if (stored == null
                    || !"AVAILABLE".equals(stored.get("status", String.class))
                    || stored.get("available_at", OffsetDateTime.class) == null
                    || stored.get("available_at", OffsetDateTime.class).isAfter(createdAt)
                    || !dataset.lockedDatasetHash().equals(prefixed(stored.get("dataset_hash", String.class)))
                    || stored.get("period_start", java.time.LocalDate.class).isAfter(period.evaluationStart())
                    || stored.get("period_end", java.time.LocalDate.class).isBefore(period.evaluationEnd())) {
                throw new IllegalArgumentException(
                        "hidden period dataset is unavailable, changed, or does not cover its period");
            }
        }
        for (var feature : period.featureMaterializations()) {
            Record stored = dsl.fetchOne(
                    "select result_hash, status::text as status, available_at, period_start, period_end "
                            + "from market_data.feature_materializations where id = ?",
                    feature.id());
            if (stored == null
                    || !"SUCCEEDED".equals(stored.get("status", String.class))
                    || stored.get("available_at", OffsetDateTime.class) == null
                    || stored.get("available_at", OffsetDateTime.class).isAfter(createdAt)
                    || !feature.lockedResultHash().equals(prefixed(stored.get("result_hash", String.class)))
                    || stored.get("period_start", OffsetDateTime.class).toLocalDate()
                            .isAfter(period.evaluationStart())
                    || stored.get("period_end", OffsetDateTime.class).toLocalDate()
                            .isBefore(period.evaluationEnd())) {
                throw new IllegalArgumentException(
                        "hidden period feature materialization is unavailable, changed, or incomplete");
            }
        }
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String prefixed(String value) {
        return value == null || value.startsWith("sha256:") ? value : "sha256:" + value;
    }
}
