package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OperatorRbacReadModels {
    private OperatorRbacReadModels() {}

    public record ActorState(
            boolean active,
            String activeCatalogVersion,
            Set<UUID> effectivePermissionIds,
            SelfView self) {
        public ActorState {
            effectivePermissionIds = Set.copyOf(effectivePermissionIds);
            Objects.requireNonNull(self, "self");
        }
    }

    public record SelfView(
            UUID operatorId,
            String catalogVersion,
            boolean currentMfa,
            Instant mfaAuthenticatedAt,
            Instant lastMfaVerifiedAt,
            List<RoleView> roles,
            List<PermissionView> permissions,
            List<AssignmentView> assignments) {
        public SelfView {
            Objects.requireNonNull(operatorId, "operatorId");
            roles = List.copyOf(roles);
            permissions = List.copyOf(permissions);
            assignments = List.copyOf(assignments);
        }

        public SelfView withCurrentMfa(boolean value, Instant authenticatedAt) {
            return new SelfView(operatorId, catalogVersion, value, authenticatedAt, lastMfaVerifiedAt,
                    roles, permissions, assignments);
        }
    }

    public record CatalogView(
            String catalogVersion,
            List<RoleView> roles,
            List<PermissionView> permissions,
            List<RolePermissionView> rolePermissions) {
        public CatalogView {
            Objects.requireNonNull(catalogVersion, "catalogVersion");
            roles = List.copyOf(roles);
            permissions = List.copyOf(permissions);
            rolePermissions = List.copyOf(rolePermissions);
        }
    }

    public record AssignmentsView(UUID operatorId, List<AssignmentView> assignments) {
        public AssignmentsView {
            Objects.requireNonNull(operatorId, "operatorId");
            assignments = List.copyOf(assignments);
        }
    }

    public record RoleView(UUID id, String code, int hierarchyRank) {
        public RoleView {
            Objects.requireNonNull(id, "id");
            requireText(code, "code");
        }
    }

    public record PermissionView(UUID id, String code) {
        public PermissionView {
            Objects.requireNonNull(id, "id");
            requireText(code, "code");
        }
    }

    public record RolePermissionView(UUID roleId, UUID permissionId, boolean delegable) {
        public RolePermissionView {
            Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(permissionId, "permissionId");
        }
    }

    public record AssignmentView(
            UUID id,
            UUID operatorId,
            UUID roleId,
            String roleCode,
            String catalogVersion,
            Instant grantedAt,
            Instant expiresAt,
            Instant revokedAt,
            String revocationReasonCode,
            AssignmentStatus status) {
        public AssignmentView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(operatorId, "operatorId");
            Objects.requireNonNull(roleId, "roleId");
            requireText(roleCode, "roleCode");
            Objects.requireNonNull(grantedAt, "grantedAt");
            Objects.requireNonNull(status, "status");
        }
    }

    public enum AssignmentStatus { ACTIVE, FUTURE, EXPIRED, REVOKED, STALE_CATALOG, UNMIGRATED }
    public enum Kind { SELF, CATALOG, ASSIGNMENTS }
    public enum DecisionStatus { SUCCEEDED, REJECTED }

    public record AuditDecision(
            Kind kind,
            UUID actorId,
            UUID targetOperatorId,
            UUID correlationId,
            String expectedCatalogVersion,
            String resolvedCatalogVersion,
            DecisionStatus decisionStatus,
            String responseCode,
            Instant evaluatedAt,
            UUID requiredPermissionId,
            Set<UUID> effectivePermissionIds,
            boolean mfaRequired,
            boolean currentMfa) {
        public AuditDecision {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(targetOperatorId, "targetOperatorId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(decisionStatus, "decisionStatus");
            requireText(responseCode, "responseCode");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            effectivePermissionIds = Set.copyOf(effectivePermissionIds);
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
