package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The versioned, deterministic request emitted once for an immutable strategy release.
 *
 * <p>The Backend registers the stable run and this request in the release transaction. Consumers
 * must execute the supplied run id and must never invent a replacement identity.
 */
public record OfficialBacktestRequest(
        MessageMetadata metadata,
        UUID runId,
        UUID botId,
        String expectedSnapshotHash,
        String compiledPlanChecksum,
        UUID datasetManifestId,
        String assumptionsVersion,
        String executionPolicyVersion,
        String requestReason) {
    public static final String CONTRACT_VERSION = "strategy-bot.v1";
    public static final String MESSAGE_TYPE = "OFFICIAL_BACKTEST_REQUESTED";
    public static final String REQUEST_REASON = "STRATEGY_RELEASE";

    public OfficialBacktestRequest {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(botId, "botId");
        requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
        requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        requireText(assumptionsVersion, "assumptionsVersion");
        requireText(executionPolicyVersion, "executionPolicyVersion");
        if (!REQUEST_REASON.equals(requestReason)) {
            throw new IllegalArgumentException("requestReason must be STRATEGY_RELEASE");
        }
    }

    /**
     * The request for a release, naming the plan that release published.
     *
     * <p>The checksum is taken from {@code release.contractPlan()} rather than accepted as an
     * argument. It used to be a parameter, and the caller supplied a second, freshly compiled
     * {@code CompiledFlowPlan.planHash()} — a digest of a different artifact than the one written to
     * {@code bot.launch_contract_plans}. The consumer resolves this checksum against exactly that
     * table, so the request named a row that was never stored and execution failed with
     * {@code JobNotSatisfiable: compiled plan ... is not resolvable} before the simulation began
     * (root #439, INT03 run {@code c0df2755}).
     *
     * <p>Reading it from the release makes the mismatch unrepresentable instead of merely checked.
     */
    public static OfficialBacktestRequest forRelease(
            ImmutableStrategyRelease release,
            UUID datasetManifestId,
            String executionPolicyVersion) {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        requireText(executionPolicyVersion, "executionPolicyVersion");
        String snapshotHash = prefixed(release.snapshotHash());
        String planChecksum = release.contractPlan().planChecksum();
        String operationKey = "OFFICIAL_BACKTEST|" + datasetManifestId + "|"
                + release.launchConfiguration().accountingRulesVersion();
        String material = String.join("\n",
                "contractVersion=" + CONTRACT_VERSION,
                "messageType=" + MESSAGE_TYPE,
                "aggregateId=" + release.botId(),
                "snapshotHash=" + snapshotHash,
                "operationKey=" + operationKey);
        String idempotencyKey = "sha256:" + StrategyDocumentJson.sha256(material);
        UUID messageId = derivedId(release.botId(), "official-backtest-message");
        UUID runId = derivedId(release.botId(), "official-backtest-run");
        var metadata = new MessageMetadata(
                CONTRACT_VERSION, MESSAGE_TYPE, messageId, release.releasedAt(), release.botId(), idempotencyKey);
        return new OfficialBacktestRequest(
                metadata, runId, release.botId(), snapshotHash, planChecksum, datasetManifestId,
                release.launchConfiguration().accountingRulesVersion(), executionPolicyVersion, REQUEST_REASON);
    }

    private static UUID derivedId(UUID botId, String component) {
        return UUID.nameUUIDFromBytes((botId + ":" + component).getBytes(StandardCharsets.UTF_8));
    }

    private static String prefixed(String hash) {
        String value = hash.startsWith("sha256:") ? hash : "sha256:" + hash;
        requireSha256(value, "hash");
        return value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireSha256(String value, String field) {
        requireText(value, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must use sha256:<64 lowercase hex>");
        }
    }

    public record MessageMetadata(
            String contractVersion,
            String messageType,
            UUID messageId,
            Instant occurredAt,
            UUID correlationId,
            String idempotencyKey) {
        public MessageMetadata {
            if (!CONTRACT_VERSION.equals(contractVersion)) {
                throw new IllegalArgumentException("Unsupported official backtest contract version");
            }
            if (!MESSAGE_TYPE.equals(messageType)) {
                throw new IllegalArgumentException("Unsupported official backtest message type");
            }
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(correlationId, "correlationId");
            requireSha256(idempotencyKey, "idempotencyKey");
        }
    }
}
