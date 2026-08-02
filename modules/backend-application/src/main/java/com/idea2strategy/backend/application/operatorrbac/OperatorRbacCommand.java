package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OperatorRbacCommand(
        Type type,
        OperatorRequestContext requestContext,
        UUID targetOperatorId,
        UUID roleId,
        UUID assignmentId,
        UUID requiredPermissionId,
        String expectedCatalogVersion,
        Instant expiresAt,
        String reasonCode,
        UUID correlationId,
        String idempotencyKey,
        String requestHash) {

    public OperatorRbacCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(requestContext, "requestContext");
        Objects.requireNonNull(targetOperatorId, "targetOperatorId");
        Objects.requireNonNull(requiredPermissionId, "requiredPermissionId");
        if (expectedCatalogVersion == null || expectedCatalogVersion.isBlank()) {
            throw new IllegalArgumentException("expectedCatalogVersion is required");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256 hex");
        }
        if (type == Type.GRANT && roleId == null) {
            throw new IllegalArgumentException("roleId is required for grant");
        }
        if (type == Type.GRANT && assignmentId != null) {
            throw new IllegalArgumentException("assignmentId is not allowed for grant");
        }
        if (type == Type.REVOKE && assignmentId == null) {
            throw new IllegalArgumentException("assignmentId is required for revoke");
        }
        if (type == Type.REVOKE && (roleId != null || expiresAt != null)) {
            throw new IllegalArgumentException("roleId and expiresAt are not allowed for revoke");
        }
    }

    public enum Type {
        GRANT,
        REVOKE
    }
}
