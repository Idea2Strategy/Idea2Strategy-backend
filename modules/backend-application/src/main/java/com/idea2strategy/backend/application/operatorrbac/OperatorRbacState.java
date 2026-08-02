package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OperatorRbacState(
        Catalog catalog,
        Operator actor,
        Operator target,
        List<Assignment> actorAssignments,
        List<Assignment> targetAssignments,
        Assignment selectedAssignment) {

    public OperatorRbacState {
        actorAssignments = List.copyOf(actorAssignments);
        targetAssignments = List.copyOf(targetAssignments);
    }

    public record Catalog(String version, Status status, Map<UUID, Role> roles, Set<UUID> permissions) {
        public Catalog {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(status, "status");
            roles = Map.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }

    public record Role(
            UUID id,
            boolean active,
            int hierarchyRank,
            Set<UUID> permissions,
            Set<UUID> delegablePermissions) {
        public Role {
            Objects.requireNonNull(id, "id");
            permissions = Set.copyOf(permissions);
            delegablePermissions = Set.copyOf(delegablePermissions);
            if (!permissions.containsAll(delegablePermissions)) {
                throw new IllegalArgumentException("delegable permissions must belong to the role");
            }
        }
    }

    public record Operator(UUID id, boolean active, boolean mfaEnrolled) {
        public Operator {
            Objects.requireNonNull(id, "id");
        }
    }

    public record Assignment(
            UUID id,
            UUID operatorId,
            UUID roleId,
            String catalogVersion,
            Instant grantedAt,
            Instant expiresAt,
            Instant revokedAt) {
        public Assignment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(operatorId, "operatorId");
            Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(catalogVersion, "catalogVersion");
            Objects.requireNonNull(grantedAt, "grantedAt");
        }

        public boolean effectiveAt(Instant now, Catalog activeCatalog) {
            return revokedAt == null
                    && !grantedAt.isAfter(now)
                    && (expiresAt == null || now.isBefore(expiresAt))
                    && activeCatalog != null
                    && activeCatalog.status() == Status.ACTIVE
                    && catalogVersion.equals(activeCatalog.version());
        }
    }

    public enum Status {
        DRAFT,
        ACTIVE,
        RETIRED
    }
}
