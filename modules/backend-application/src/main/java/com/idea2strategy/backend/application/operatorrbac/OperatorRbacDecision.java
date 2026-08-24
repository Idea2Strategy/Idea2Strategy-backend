package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface OperatorRbacDecision {
    OperatorRbacResult decide(OperatorRbacState state);

    record Evidence(
            String catalogVersion,
            Set<UUID> actorRoleIds,
            Set<UUID> actorPermissionIds,
            Set<UUID> actorDelegablePermissionIds,
            Set<UUID> targetRolePermissionIds,
            boolean sessionAuthenticated,
            boolean mfaSatisfied,
            boolean strictHierarchySatisfied) {
        public Evidence {
            actorRoleIds = Set.copyOf(actorRoleIds);
            actorPermissionIds = Set.copyOf(actorPermissionIds);
            actorDelegablePermissionIds = Set.copyOf(actorDelegablePermissionIds);
            targetRolePermissionIds = Set.copyOf(targetRolePermissionIds);
        }
    }

    record Mutation(
            Kind kind,
            UUID targetOperatorId,
            UUID roleId,
            UUID assignmentId,
            String catalogVersion,
            Instant grantedAt,
            Instant expiresAt,
            Instant revokedAt,
            UUID changedByOperatorId,
            String reasonCode) {
        public enum Kind {
            GRANT,
            REVOKE
        }
    }
}
