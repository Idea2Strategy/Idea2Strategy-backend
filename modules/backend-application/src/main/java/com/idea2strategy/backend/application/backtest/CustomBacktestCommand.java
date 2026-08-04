package com.idea2strategy.backend.application.backtest;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record CustomBacktestCommand(
        UUID botId,
        UUID datasetManifestId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String executionPolicyVersion,
        String idempotencyKey) {
    public CustomBacktestCommand {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not precede periodStart");
        }
        if (executionPolicyVersion == null || executionPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("executionPolicyVersion must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
