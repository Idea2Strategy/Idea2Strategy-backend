package com.idea2strategy.backend.application.operatorbootstrap;

import java.util.List;
import java.util.UUID;

public record OperatorBootstrapManifest(
        String bootstrapKey,
        String catalogVersion,
        String catalogContentHash,
        String expectedDatabaseRole,
        short externalIdentityKeyVersion,
        String externalIdentityKeyHmac,
        UUID operatorAccountId,
        UUID operatorRoleAssignmentId,
        UUID initialRoleId,
        UUID deploymentActorId,
        String grantProvenance,
        UUID correlationId,
        UUID auditEventId,
        List<Role> roles,
        List<Permission> permissions,
        List<RolePermission> rolePermissions) {

    public OperatorBootstrapManifest {
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
        rolePermissions = List.copyOf(rolePermissions);
    }

    public record Role(UUID id, String code, int hierarchyRank) {}
    public record Permission(UUID id, String code, String description, String sensitivity) {}
    public record RolePermission(UUID roleId, UUID permissionId, boolean delegable) {}
}
