package com.idea2strategy.backend.application.batch;

import java.util.Objects;
import java.util.UUID;

public interface BatchFailureHandoffPort {
    enum FailureDisposition { RETRY, DEAD_LETTER }

    record Failure(
            BatchCategory category,
            String itemId,
            String idempotencyKey,
            int attemptNumber,
            UUID claimToken,
            String failureCode,
            FailureDisposition disposition,
            String runtimePolicyVersion,
            UUID runId,
            UUID correlationId) {
        public Failure {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(claimToken, "claimToken");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(correlationId, "correlationId");
            if (itemId == null || itemId.isBlank()
                    || idempotencyKey == null || idempotencyKey.isBlank()
                    || failureCode == null || failureCode.isBlank()
                    || runtimePolicyVersion == null || runtimePolicyVersion.isBlank()) {
                throw new IllegalArgumentException("failure evidence must be complete");
            }
        }
    }

    void handoff(Failure failure);
}
