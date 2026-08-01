package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The versioned, deterministic request emitted once for an immutable strategy release. */
public record OfficialBacktestRequest(
        MessageMetadata metadata,
        UUID botId,
        String expectedSnapshotHash,
        String compiledPlanChecksum,
        UUID datasetManifestId,
        String assumptionsVersion,
        String requestReason) {
    public static final String CONTRACT_VERSION = "strategy-bot.v1";
    public static final String MESSAGE_TYPE = "OFFICIAL_BACKTEST_REQUESTED";
    public static final String REQUEST_REASON = "STRATEGY_RELEASE";

    public OfficialBacktestRequest {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(botId, "botId");
        requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
        requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        requireText(assumptionsVersion, "assumptionsVersion");
        if (!REQUEST_REASON.equals(requestReason)) {
            throw new IllegalArgumentException("requestReason must be STRATEGY_RELEASE");
        }
    }

    public static OfficialBacktestRequest forRelease(
            ImmutableStrategyRelease release,
            String compiledPlanHash,
            UUID datasetManifestId) {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        String snapshotHash = prefixed(release.snapshotHash());
        String planChecksum = prefixed(compiledPlanHash);
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
        var metadata = new MessageMetadata(
                CONTRACT_VERSION, MESSAGE_TYPE, messageId, release.releasedAt(), release.botId(), idempotencyKey);
        return new OfficialBacktestRequest(
                metadata, release.botId(), snapshotHash, planChecksum, datasetManifestId,
                release.launchConfiguration().accountingRulesVersion(), REQUEST_REASON);
    }

    public UUID runId() {
        return derivedId(botId, "official-backtest-run");
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
