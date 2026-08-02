package com.idea2strategy.backend.messaging.backtest.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BacktestResultContractFixture(
        Metadata metadata,
        UUID backtestRunId,
        UUID botId,
        UUID ownerAccountId,
        String expectedSnapshotHash,
        String inputBundleFingerprint,
        String executionPolicyVersion,
        String precisionRulesVersion,
        String status,
        Instant completedAt,
        int attempt,
        UUID resultManifestId,
        String resultHash,
        String source,
        String eventType,
        boolean livePerformanceEligible) {

    public BacktestResultContractFixture {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(backtestRunId, "backtestRunId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        requireHash(expectedSnapshotHash, "expectedSnapshotHash");
        requireHash(inputBundleFingerprint, "inputBundleFingerprint");
        requireText(executionPolicyVersion, "executionPolicyVersion");
        requireText(precisionRulesVersion, "precisionRulesVersion");
        if (!"COMPLETED".equals(status) || completedAt == null || attempt < 1 || resultManifestId == null) {
            throw new IllegalArgumentException("completed backtest result fields are invalid");
        }
        requireHash(resultHash, "resultHash");
        if (!"BACKTEST".equals(source)
                || !"BACKTEST_RESULT".equals(eventType)
                || livePerformanceEligible) {
            throw new IllegalArgumentException("backtest result must remain ineligible for live performance");
        }
    }

    public record Metadata(
            String contractVersion,
            String messageType,
            UUID messageId,
            Instant occurredAt,
            UUID correlationId,
            String idempotencyKey) {
        public Metadata {
            if (!"backtest.v1".equals(contractVersion)
                    || !"BACKTEST_COMPLETED".equals(messageType)
                    || messageId == null
                    || occurredAt == null
                    || correlationId == null) {
                throw new IllegalArgumentException("unsupported backtest result metadata");
            }
            requireHash(idempotencyKey, "idempotencyKey");
        }
    }

    private static String requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a sha256 digest");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
