package com.idea2strategy.backend.application.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface BatchCategoryPort {
    enum ItemStatus {
        COMPLETED,
        ALREADY_COMPLETED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record Cursor(Instant dueAt, String stableId) {
        public Cursor {
            Objects.requireNonNull(dueAt, "dueAt");
            requireText(stableId, "stableId");
        }
    }

    record ClaimRequest(
            String workerId,
            String runtimePolicyVersion,
            Duration leaseDuration,
            Cursor after,
            int limit,
            UUID runId,
            UUID correlationId) {
        public ClaimRequest {
            requireText(workerId, "workerId");
            requireText(runtimePolicyVersion, "runtimePolicyVersion");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (limit < 1) throw new IllegalArgumentException("limit must be positive");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    record WorkItem(
            BatchCategory category,
            String itemId,
            Instant dueAt,
            String idempotencyKey,
            UUID claimToken,
            int attemptNumber) {
        public WorkItem {
            Objects.requireNonNull(category, "category");
            requireText(itemId, "itemId");
            Objects.requireNonNull(dueAt, "dueAt");
            requireText(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(claimToken, "claimToken");
            if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        }
    }

    record ClaimPage(Instant databaseNow, List<WorkItem> items, Cursor nextCursor) {
        public ClaimPage {
            Objects.requireNonNull(databaseNow, "databaseNow");
            items = List.copyOf(items);
        }
    }

    record ItemResult(ItemStatus status, String failureCode) {
        public ItemResult {
            Objects.requireNonNull(status, "status");
            boolean failed = status == ItemStatus.RETRYABLE_FAILURE
                    || status == ItemStatus.PERMANENT_FAILURE;
            if (failed) requireText(failureCode, "failureCode");
            if (!failed && failureCode != null) {
                throw new IllegalArgumentException("successful result cannot have failureCode");
            }
        }

        public static ItemResult completed() {
            return new ItemResult(ItemStatus.COMPLETED, null);
        }

        public static ItemResult alreadyCompleted() {
            return new ItemResult(ItemStatus.ALREADY_COMPLETED, null);
        }

        public static ItemResult retryable(String code) {
            return new ItemResult(ItemStatus.RETRYABLE_FAILURE, code);
        }

        public static ItemResult permanent(String code) {
            return new ItemResult(ItemStatus.PERMANENT_FAILURE, code);
        }
    }

    BatchCategory category();

    ClaimPage claimDue(ClaimRequest request);

    ItemResult execute(WorkItem item, UUID runId, UUID correlationId);

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
