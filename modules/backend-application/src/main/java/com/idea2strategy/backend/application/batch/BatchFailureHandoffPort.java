package com.idea2strategy.backend.application.batch;

import java.util.Objects;
import java.util.UUID;

public interface BatchFailureHandoffPort {
    enum FailureDisposition { RETRY, DEAD_LETTER }

    /**
     * @param failureCode the stable audit vocabulary. It is written to
     *     {@code operations.audit_events.reason_code}, so it must stay a closed set of codes a
     *     reviewer can filter on.
     * @param diagnostic what actually went wrong, for an operator rather than for the audit
     *     vocabulary — {@code null} when the failure was already classified. A deterministic failure
     *     used to be recorded only as {@code UNCLASSIFIED_EXECUTION_FAILURE} with the exception
     *     discarded, so an expired sanction retried every minute for three hours and nothing said
     *     why (#264). This field carries the cause to the adapter that can log it, without widening
     *     the codes the audit trail is filtered by.
     */
    record Failure(
            BatchCategory category,
            String itemId,
            String idempotencyKey,
            int attemptNumber,
            UUID claimToken,
            String failureCode,
            String diagnostic,
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
