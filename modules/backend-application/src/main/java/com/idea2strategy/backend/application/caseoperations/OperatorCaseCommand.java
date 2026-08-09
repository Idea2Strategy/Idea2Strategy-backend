package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
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
        String customerMessage,
        List<UUID> evidenceIds,
        UUID sanctionId,
        AccountSanctionState.Type sanctionType,
        Instant sanctionExpiresAt,
        long expectedSanctionVersion,
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
        customerMessage = customerMessage == null ? null : customerMessage.trim();
        if (customerMessage != null && customerMessage.length() > 2000) {
            throw new IllegalArgumentException("customerMessage must not exceed 2000 characters");
        }
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
        if (expectedSanctionVersion < 0) {
            throw new IllegalArgumentException("expectedSanctionVersion must not be negative");
        }
        if (action == Action.APPLY_SANCTION) {
            Objects.requireNonNull(sanctionType, "sanctionType");
            if (sanctionType == AccountSanctionState.Type.SUSPENSION && sanctionExpiresAt == null) {
                throw new IllegalArgumentException("temporary sanctions require sanctionExpiresAt");
            }
            if (sanctionType == AccountSanctionState.Type.PERMANENT && sanctionExpiresAt != null) {
                throw new IllegalArgumentException("permanent sanctions cannot expire");
            }
        } else if (sanctionType != null || sanctionExpiresAt != null) {
            throw new IllegalArgumentException("only APPLY_SANCTION may define sanction type or expiry");
        }
    }

    public OperatorCaseCommand(
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
        this(action, requestContext, caseId, expectedVersion, assigneeOperatorId, requiredPermissionId,
                reasonCode, null, evidenceIds, sanctionId,
                action == Action.APPLY_SANCTION ? AccountSanctionState.Type.PERMANENT : null,
                null, 0, correlationId, idempotencyKey, requestHash);
    }

    public OperatorCaseCommand(
            Action action,
            OperatorRequestContext requestContext,
            UUID caseId,
            long expectedVersion,
            UUID assigneeOperatorId,
            UUID requiredPermissionId,
            String reasonCode,
            List<UUID> evidenceIds,
            UUID sanctionId,
            AccountSanctionState.Type sanctionType,
            Instant sanctionExpiresAt,
            long expectedSanctionVersion,
            UUID correlationId,
            String idempotencyKey,
            String requestHash) {
        this(action, requestContext, caseId, expectedVersion, assigneeOperatorId,
                requiredPermissionId, reasonCode, null, evidenceIds, sanctionId, sanctionType,
                sanctionExpiresAt, expectedSanctionVersion, correlationId, idempotencyKey, requestHash);
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
