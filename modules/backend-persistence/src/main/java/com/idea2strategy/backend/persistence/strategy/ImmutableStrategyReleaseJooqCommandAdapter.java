package com.idea2strategy.backend.persistence.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.backtest.BacktestRequestIdempotencyConflictException;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandPort;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import com.idea2strategy.backend.application.strategy.OfficialBacktestRequest;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import com.idea2strategy.backend.persistence.backtest.BacktestRunInputPinWriter;
import com.idea2strategy.backend.persistence.backtest.BacktestRunInputPinWriter.DatasetPin;
import com.idea2strategy.backend.persistence.backtest.BacktestRunInputPinWriter.FeaturePin;
import com.idea2strategy.backend.persistence.backtest.BacktestRunInputPinWriter.RunInputPin;
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ImmutableStrategyReleaseJooqCommandAdapter implements ImmutableStrategyReleaseCommandPort {
    private final DSLContext dsl;
    private final FeatureMaterializationPinResolver featurePins;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImmutableStrategyReleaseJooqCommandAdapter(
            DSLContext dsl,
            FeatureMaterializationPinResolver featurePins) {
        this.dsl = dsl;
        this.featurePins = featurePins;
    }

    @Override
    @Transactional
    public ImmutableStrategyRelease saveOnce(
            ImmutableStrategyRelease release,
            OfficialBacktestRequest backtestRequest,
            UUID validationRunId,
            long validatedEditSequence,
            String validatedSemanticHash) {
        dsl.fetchOne(
                "select pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                release.botId());
        var existing = dsl.fetchOne(
                "select s.snapshot_hash, b.owner_account_id from bot.launch_snapshots s "
                        + "join bot.bots b on b.id = s.bot_id where s.bot_id = ?",
                release.botId());
        String existingHash = existing == null ? null : existing.get("snapshot_hash", String.class);
        if (existingHash != null) {
            UUID existingOwnerId = existing.get("owner_account_id", UUID.class);
            if (!release.ownerAccountId().equals(existingOwnerId)
                    || !existingHash.equals(release.snapshotHash())) {
                throw new ImmutableStrategyReleaseRejectedException(
                        "Release id is already bound to different immutable content");
            }
            saveOfficialBacktestOnce(release, backtestRequest);
            return release;
        }

        var locked = dsl.fetchOne(
                "select d.edit_sequence, d.semantic_hash "
                        + "from strategy.validation_runs v "
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
                    "Strategy validation became stale before release");
        }

        for (var flow : release.partition().flows()) {
            var pinnedPlan = dsl.fetchOne(
                    "select 1 from strategy.compiled_flow_plans p "
                            + "join strategy.validation_runs v on v.id = ? "
                            + "join strategy.element_catalog_versions c on c.id = p.element_catalog_version_id "
                            + "where p.id = ? and p.semantic_hash = v.semantic_hash "
                            + "and p.element_catalog_version_id = v.element_catalog_version_id "
                            + "and p.element_catalog_version_id = ? "
                            + "and c.published_at <= ?::timestamptz "
                            + "and (c.retired_at is null or c.retired_at > ?::timestamptz)",
                    validationRunId,
                    flow.compiledFlowPlanId(),
                    flow.elementCatalogVersionId(),
                    release.releasedAt().atOffset(ZoneOffset.UTC),
                    release.releasedAt().atOffset(ZoneOffset.UTC));
            if (pinnedPlan == null) {
                throw new ImmutableStrategyReleaseRejectedException(
                        "Compiled flow plan or catalog does not match the validated strategy");
            }
        }

        var config = release.launchConfiguration();
        var releasedAt = release.releasedAt().atOffset(ZoneOffset.UTC);
        var activePolicies = dsl.fetchOne(
                "select 1 from trading.fee_policy_versions f "
                        + "join trading.buying_power_buffer_policy_versions b on b.id = ? "
                        + "where f.id = ? and f.effective_from <= ?::timestamptz "
                        + "and (f.effective_to is null or f.effective_to > ?::timestamptz) "
                        + "and b.effective_from <= ?::timestamptz "
                        + "and (b.effective_to is null or b.effective_to > ?::timestamptz)",
                config.buyingPowerBufferPolicyId(),
                config.feePolicyId(),
                releasedAt,
                releasedAt,
                releasedAt,
                releasedAt);
        if (activePolicies == null) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Launch policies must be effective at the release instant");
        }

        var at = releasedAt;
        dsl.execute(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, ?::strategy.strategy_mode, ?, ?::bot.lifecycle_status, "
                        + "?::timestamptz, ?::timestamptz, ?::timestamptz, 0, ?::timestamptz)",
                release.botId(), release.ownerAccountId(), "BASIC", release.name(), "RUNNING", at, at, at, at);
        dsl.execute(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::timestamptz)",
                release.botId(), "basic-launch-snapshot.v1", release.semanticSnapshot(),
                release.presentationSnapshot(), release.semanticHash(), release.presentationHash(),
                release.snapshotHash(), at);
        insertContractPlan(release, at);

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
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?::timestamptz, ?::timestamptz)",
                partition.id(), release.botId(), partition.name(), partition.description(), partition.budgetCapBps(),
                BigDecimal.ZERO, BigDecimal.ZERO, partition.configurationHash(), at, at);

        for (var flow : partition.flows()) {
            BigDecimal x = BigDecimal.valueOf((long) flow.positionOrder() * 240L);
            dsl.execute(
                    "insert into bot.flows "
                            + "(id, partition_id, name, element_catalog_version_id, compiled_flow_plan_id, "
                            + "position_x, position_y, semantic_document, layout_document, layout_schema_version, "
                            + "semantic_hash, layout_hash, configuration_hash, edit_sequence, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, 0, "
                            + "?::timestamptz, ?::timestamptz)",
                    flow.id(), partition.id(), flow.name(), flow.elementCatalogVersionId(), flow.compiledFlowPlanId(),
                    x, BigDecimal.ZERO, flow.semanticDocument(), flow.layoutDocument(), "basic-flow-layout.v1",
                    flow.semanticHash(), flow.layoutHash(), flow.configurationHash(), at, at);
            for (UUID instrumentId : flow.instrumentIds()) {
                dsl.execute(
                        "insert into bot.flow_instruments (flow_id, instrument_id) values (?, ?)",
                        flow.id(), instrumentId);
            }
            for (var requirement : flow.featureRequirements()) {
                dsl.execute(
                        "insert into bot.flow_feature_requirements "
                                + "(flow_id, instrument_id, feature_definition_id) values (?, ?, ?)",
                        flow.id(), requirement.instrumentId(), requirement.featureDefinitionId());
            }
        }
        saveOfficialBacktestOnce(release, backtestRequest);
        return release;
    }

    /**
     * The published compiled plan, written in the same transaction as the snapshot it pins.
     *
     * <p>Root #190: the evaluation runtime reads this row to load a bot. Splitting it from the snapshot
     * write would leave a window where a bot exists, its RUN command is publishable, and the plan the
     * command names cannot be found.
     */
    private void insertContractPlan(ImmutableStrategyRelease release, OffsetDateTime at) {
        var plan = release.contractPlan();
        dsl.execute(
                "insert into bot.launch_contract_plans "
                        + "(bot_id, contract_version, plan_schema_version, plan_checksum, plan_document, created_at) "
                        + "values (?, ?, ?, ?, ?::jsonb, ?::timestamptz)",
                release.botId(), plan.contractVersion(), plan.planSchemaVersion(), plan.planChecksum(),
                plan.planDocument(), at);
    }

    /**
     * Registers the immutable BASIC run and its lane Outbox event in the release transaction.
     */
    private void saveOfficialBacktestOnce(
            ImmutableStrategyRelease release,
            OfficialBacktestRequest request) {
        if (!release.botId().equals(request.botId())
                || !request.expectedSnapshotHash().equals("sha256:" + release.snapshotHash())
                || !request.assumptionsVersion().equals(release.launchConfiguration().accountingRulesVersion())) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Official backtest request does not match the immutable release");
        }

        String idempotencyKey = request.metadata().idempotencyKey();

        var dataset = dsl.fetchOne(
                "select dataset_hash, period_start::date as period_start, period_end::date as period_end "
                        + "from market_data.dataset_manifests "
                        + "where id = ? and status = 'AVAILABLE' and available_at is not null "
                        + "and available_at <= ?::timestamptz",
                request.datasetManifestId(), release.releasedAt().atOffset(ZoneOffset.UTC));
        if (dataset == null) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Official backtest dataset must be available at the release instant");
        }
        boolean policyAvailable = dsl.fetchOne(
                "select 1 from backtest.execution_policy_versions "
                        + "where version = ? and locked_at <= ?::timestamptz",
                request.executionPolicyVersion(), release.releasedAt().atOffset(ZoneOffset.UTC)) != null;
        if (!policyAvailable) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Official backtest execution policy must be locked at the release instant");
        }
        var queuedAt = release.releasedAt().atOffset(ZoneOffset.UTC);
        String expectedDatasetHash = prefixed(dataset.get("dataset_hash", String.class));
        java.time.LocalDate periodStart = dataset.get("period_start", java.time.LocalDate.class);
        java.time.LocalDate periodEnd = dataset.get("period_end", java.time.LocalDate.class);
        final List<FeaturePin> resolvedFeatures;
        try {
            resolvedFeatures = featurePins.resolve(
                    release.contractPlan().planDocument(), periodStart, periodEnd, queuedAt);
        } catch (IllegalStateException exception) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Official backtest feature pins are not publishable: " + exception.getMessage());
        }
        BasicPayload basicPayload = payloadDocument(
                request, expectedDatasetHash, periodStart, periodEnd, resolvedFeatures);
        String payload = basicPayload.document();
        var configuration = release.launchConfiguration();

        dsl.execute(
                "insert into backtest.runs (id, lane, message_id, bot_id, owner_account_id, "
                        + "configuration_hash, canonical_payload_hash, aggregate_sequence, status, "
                        + "evaluation_start, evaluation_end, initial_cash_amount, market_rules_version, "
                        + "accounting_rules_version, execution_policy_version, precision_rules_version, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, idempotency_scope, "
                        + "idempotency_key, queued_at) values (?, 'BASIC', ?, ?, ?, ?, "
                        + "encode(sha256(convert_to((?::jsonb)::text, 'UTF8')), 'hex'), 1, 'QUEUED', "
                        + "?, ?, ?, ?, ?, ?, ?, ?, 5, ?, ?, ?, ?::timestamptz) "
                        + "on conflict (lane, idempotency_scope, idempotency_key) do nothing",
                request.runId(), request.metadata().messageId(), request.botId(), release.ownerAccountId(),
                configuration.configurationHash(), payload,
                periodStart, periodEnd, configuration.initialCashAmount(),
                configuration.brokerRulesVersion(), configuration.accountingRulesVersion(),
                request.executionPolicyVersion(), configuration.precisionRulesVersion(),
                configuration.feePolicyId(), configuration.buyingPowerBufferPolicyId(),
                release.botId().toString(), idempotencyKey, queuedAt);

        BacktestRunInputPinWriter.pin(dsl, new RunInputPin(
                request.runId(), basicPayload.requestHash(), request.metadata().contractVersion(),
                request.compiledPlanChecksum(), request.expectedSnapshotHash(), request.executionPolicyVersion(),
                queuedAt,
                List.of(new DatasetPin(request.datasetManifestId(), "MARKET_BARS", expectedDatasetHash)),
                resolvedFeatures));

        var existingOutbox = dsl.fetchOne(
                "select event_type, event_schema_version, payload_document ->> 'requestHash' as request_hash "
                        + "from operations.outbox_messages where idempotency_key = ? for update",
                idempotencyKey);
        if (existingOutbox != null) {
            if (!request.metadata().messageType().equals(existingOutbox.get("event_type", String.class))
                    || !request.metadata().contractVersion().equals(
                            existingOutbox.get("event_schema_version", String.class))
                    || !basicPayload.requestHash().equals(existingOutbox.get("request_hash", String.class))) {
                throw new BacktestRequestIdempotencyConflictException();
            }
            return;
        }

        // aggregate_id is the bot, not the run. SqsOutboxMessagePublisher publishes this column
        // verbatim as the envelope aggregateId, and the BASIC consumer requires it to equal the
        // payload botId — a row written under runId is rejected as TRANSPORT_ENVELOPE_MISMATCH and
        // dead-lettered before a single attempt is recorded (backend #243). The release's own
        // idempotency key material already declares the aggregate this way
        // (OfficialBacktestRequest.forRelease), and every other strategy-bot producer
        // (BotRunCommandJooqAdapter, BotStopCommandJooqAdapter) uses the bot id too; this insert was
        // the lone outlier. backtest.runs.id stays runId — only the transport aggregate changes.
        dsl.execute(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                        + "payload_document, idempotency_key, created_at) "
                        + "values (?, 'strategy-bot', ?, 1, ?, ?, ?::jsonb, ?, ?::timestamptz) "
                + "on conflict (idempotency_key) do nothing",
                request.metadata().messageId(), request.botId(), request.metadata().messageType(),
                request.metadata().contractVersion(), payload, idempotencyKey, queuedAt);
    }

    private BasicPayload payloadDocument(
            OfficialBacktestRequest request,
            String expectedDatasetHash,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd,
            List<FeaturePin> resolvedFeatures) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode metadata = root.putObject("metadata");
        metadata.put("contractVersion", request.metadata().contractVersion());
        metadata.put("messageType", request.metadata().messageType());
        metadata.put("messageId", request.metadata().messageId().toString());
        metadata.put("occurredAt", request.metadata().occurredAt().toString());
        metadata.put("correlationId", request.metadata().correlationId().toString());
        metadata.put("idempotencyKey", request.metadata().idempotencyKey());
        root.put("botId", request.botId().toString());
        root.put("runId", request.runId().toString());
        root.put("lane", "BASIC");
        root.put("aggregateSequence", 1);
        root.put("expectedSnapshotHash", request.expectedSnapshotHash());
        root.put("compiledPlanChecksum", request.compiledPlanChecksum());
        root.put("datasetManifestId", request.datasetManifestId().toString());
        root.put("expectedDatasetHash", expectedDatasetHash);
        root.put("periodStart", periodStart.toString());
        root.put("periodEnd", periodEnd.toString());
        root.put("assumptionsVersion", request.assumptionsVersion());
        root.put("executionPolicyVersion", request.executionPolicyVersion());
        root.put("requestReason", request.requestReason());
        var features = root.putArray("featureMaterializations");
        resolvedFeatures.stream()
                .sorted(java.util.Comparator.comparing(feature -> feature.featureMaterializationId().toString()))
                .forEach(feature -> {
                    var node = features.addObject();
                    node.put("featureMaterializationId", feature.featureMaterializationId().toString());
                    node.put("lockedResultHash", feature.lockedResultHash());
                });
        try {
            String requestHash = basicRequestHash(
                    request, expectedDatasetHash, periodStart, periodEnd, resolvedFeatures);
            root.put("requestHash", requestHash);
            return new BasicPayload(
                    requestHash,
                    StrategyDocumentJson.canonicalize(objectMapper.writeValueAsString(root)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Official backtest request could not be serialized", exception);
        }
    }

    static String basicRequestHash(
            OfficialBacktestRequest request,
            String expectedDatasetHash,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd,
            List<FeaturePin> resolvedFeatures) {
        StringBuilder material = new StringBuilder(String.join("\n",
                request.metadata().contractVersion(),
                request.metadata().messageType(),
                request.runId().toString(),
                request.botId().toString(),
                request.expectedSnapshotHash(),
                request.compiledPlanChecksum(),
                request.datasetManifestId().toString(),
                expectedDatasetHash,
                periodStart.toString(),
                periodEnd.toString(),
                request.assumptionsVersion(),
                request.executionPolicyVersion(),
                request.requestReason()));
        resolvedFeatures.stream()
                .sorted(java.util.Comparator.comparing(feature -> feature.featureMaterializationId().toString()))
                .forEach(feature -> material.append('\n').append(feature.featureMaterializationId())
                        .append('\n').append(feature.lockedResultHash()));
        return "sha256:" + StrategyDocumentJson.sha256(material.toString());
    }

    private static String prefixed(String value) {
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

    private record BasicPayload(String requestHash, String document) {}
}
