package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OperatorCaseCommand(
        Action action,
        OperatorRequestContext requestContext,
        UUID caseId,
        long expectedVersion,
        UUID assigneeOperatorId,
        UUID requiredPermissionId,
        String reasonCode,
        List<UUID> evidenceIds,
        UUID sanctionId,
        UUID correlationId,
        String idempotencyKey,
        String requestHash) {
    public OperatorCaseCommand {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestContext, "requestContext");
        Objects.requireNonNull(caseId, "caseId");
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        Objects.requireNonNull(requiredPermissionId, "requiredPermissionId");
        requireText(reasonCode, "reasonCode");
        evidenceIds = List.copyOf(evidenceIds);
        Objects.requireNonNull(correlationId, "correlationId");
        requireText(idempotencyKey, "idempotencyKey");
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256 hex");
        }
        if ((action == Action.ASSIGN || action == Action.REASSIGN) && assigneeOperatorId == null) {
            throw new IllegalArgumentException("assigneeOperatorId is required");
        }
        if (action != Action.ASSIGN && action != Action.REASSIGN && assigneeOperatorId != null) {
            throw new IllegalArgumentException("assigneeOperatorId is not allowed for this action");
        }
        if ((action == Action.APPLY_SANCTION || action == Action.RELEASE_SANCTION) && sanctionId == null) {
            throw new IllegalArgumentException("sanctionId is required");
        }
        if (action != Action.APPLY_SANCTION && action != Action.RELEASE_SANCTION && sanctionId != null) {
            throw new IllegalArgumentException("sanctionId is not allowed for this action");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public enum Action {
        ASSIGN,
        REASSIGN,
        UNASSIGN,
        START_REVIEW,
        REQUEST_INFORMATION,
        RESOLVE,
        REJECT,
        APPLY_SANCTION,
        RELEASE_SANCTION
    }
}
