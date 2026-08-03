package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.RoomStrategyBotProvisioningPort;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomStrategyBotProvisioningJooqAdapter implements RoomStrategyBotProvisioningPort {
    private final DSLContext dsl;

    public RoomStrategyBotProvisioningJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID provision(
            ImmutableStrategyRelease release,
            UUID validationRunId,
            long validatedEditSequence,
            String validatedSemanticHash,
            Instant executionEligibleFrom) {
        var locked = dsl.fetchOne(
                "select d.edit_sequence from strategy.validation_runs v "
                        + "join strategy.strategies s on s.id = v.strategy_id "
                        + "join strategy.strategy_documents d on d.strategy_id = s.id "
                        + "where v.id = ? and v.requested_by_account_id = ? and s.owner_account_id = ? "
                        + "and v.status = 'VALID' and v.requested_edit_sequence = ? and v.semantic_hash = ? "
                        + "and d.edit_sequence = v.requested_edit_sequence and d.semantic_hash = v.semantic_hash "
                        + "for update of d",
                validationRunId,
                release.ownerAccountId(),
                release.ownerAccountId(),
                validatedEditSequence,
                validatedSemanticHash);
        if (locked == null) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Strategy validation became stale before room admission");
        }
        for (var flow : release.partition().flows()) {
            var pinnedPlan = dsl.fetchOne(
                    "select 1 from strategy.compiled_flow_plans p "
                            + "join strategy.validation_runs v on v.id = ? "
                            + "where p.id = ? and p.semantic_hash = v.semantic_hash "
                            + "and p.element_catalog_version_id = v.element_catalog_version_id "
                            + "and p.element_catalog_version_id = ?",
                    validationRunId,
                    flow.compiledFlowPlanId(),
                    flow.elementCatalogVersionId());
            if (pinnedPlan == null) {
                throw new ImmutableStrategyReleaseRejectedException(
                        "Compiled flow plan does not match the validated strategy");
            }
        }

        var config = release.launchConfiguration();
        var admittedAt = release.releasedAt().atOffset(ZoneOffset.UTC);
        var activePolicies = dsl.fetchOne(
                "select 1 from trading.fee_policy_versions f "
                        + "join trading.buying_power_buffer_policy_versions b on b.id = ? "
                        + "where f.id = ? and f.effective_from <= ?::timestamptz "
                        + "and (f.effective_to is null or f.effective_to > ?::timestamptz) "
                        + "and b.effective_from <= ?::timestamptz "
                        + "and (b.effective_to is null or b.effective_to > ?::timestamptz)",
                config.buyingPowerBufferPolicyId(),
                config.feePolicyId(),
                admittedAt,
                admittedAt,
                admittedAt,
                admittedAt);
        if (activePolicies == null) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Room launch policies must be effective at admission");
        }

        var eligibleAt = executionEligibleFrom.atOffset(ZoneOffset.UTC);
        dsl.execute(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC'::strategy.strategy_mode, ?, 'RUNNING'::bot.lifecycle_status, "
                        + "?::timestamptz, ?::timestamptz, ?::timestamptz, 0, ?::timestamptz)",
                release.botId(), release.ownerAccountId(), release.name(), admittedAt, admittedAt, eligibleAt, admittedAt);
        dsl.execute(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', ?::jsonb, ?::jsonb, ?, ?, ?, ?::timestamptz)",
                release.botId(), release.semanticSnapshot(), release.presentationSnapshot(), release.semanticHash(),
                release.presentationHash(), release.snapshotHash(), admittedAt);
        // Root #190: a room bot is evaluated by the same runtime as a personal bot, so it needs the same
        // published plan. Written beside the snapshot it pins, for the same reason.
        var contractPlan = release.contractPlan();
        dsl.execute(
                "insert into bot.launch_contract_plans "
                        + "(bot_id, contract_version, plan_schema_version, plan_checksum, plan_document, created_at) "
                        + "values (?, ?, ?, ?, ?::jsonb, ?::timestamptz)",
                release.botId(), contractPlan.contractVersion(), contractPlan.planSchemaVersion(),
                contractPlan.planChecksum(), contractPlan.planDocument(), admittedAt);
        dsl.execute(
                "insert into bot.launch_configurations "
                        + "(bot_id, initial_cash_amount, currency_code, broker_rules_version, "
                        + "accounting_rules_version, precision_rules_version, fee_policy_id, slippage_rate_bps, "
                        + "buying_power_buffer_policy_id, candidate_conflict_policy, configuration_hash) "
                        + "values (?, ?, 'USD', ?, ?, ?, ?, 5, ?, ?::jsonb, ?)",
                release.botId(), config.initialCashAmount(), config.brokerRulesVersion(),
                config.accountingRulesVersion(), config.precisionRulesVersion(), config.feePolicyId(),
                config.buyingPowerBufferPolicyId(), config.candidateConflictPolicy(), config.configurationHash());

        var partition = release.partition();
        dsl.execute(
                "insert into bot.bot_partitions "
                        + "(id, bot_id, name, description, budget_cap_bps, position_x, position_y, "
                        + "configuration_hash, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, 0, 0, ?, 0, ?::timestamptz, ?::timestamptz)",
                partition.id(), release.botId(), partition.name(), partition.description(), partition.budgetCapBps(),
                partition.configurationHash(), admittedAt, admittedAt);
        for (var flow : partition.flows()) {
            BigDecimal x = BigDecimal.valueOf((long) flow.positionOrder() * 240L);
            dsl.execute(
                    "insert into bot.flows "
                            + "(id, partition_id, name, element_catalog_version_id, compiled_flow_plan_id, "
                            + "position_x, position_y, semantic_document, layout_document, layout_schema_version, "
                            + "semantic_hash, layout_hash, configuration_hash, edit_sequence, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, 0, ?::jsonb, ?::jsonb, 'basic-flow-layout.v1', ?, ?, ?, 0, "
                            + "?::timestamptz, ?::timestamptz)",
                    flow.id(), partition.id(), flow.name(), flow.elementCatalogVersionId(), flow.compiledFlowPlanId(),
                    x, flow.semanticDocument(), flow.layoutDocument(), flow.semanticHash(), flow.layoutHash(),
                    flow.configurationHash(), admittedAt, admittedAt);
            for (UUID instrumentId : flow.instrumentIds()) {
                dsl.execute("insert into bot.flow_instruments (flow_id, instrument_id) values (?, ?)",
                        flow.id(), instrumentId);
            }
            for (var requirement : flow.featureRequirements()) {
                dsl.execute(
                        "insert into bot.flow_feature_requirements "
                                + "(flow_id, instrument_id, feature_definition_id) values (?, ?, ?)",
                        flow.id(), requirement.instrumentId(), requirement.featureDefinitionId());
            }
        }
        return release.botId();
    }
}
