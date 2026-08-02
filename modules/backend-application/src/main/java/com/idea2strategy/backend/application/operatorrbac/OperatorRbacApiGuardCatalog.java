package com.idea2strategy.backend.application.operatorrbac;

import java.util.UUID;

public interface OperatorRbacApiGuardCatalog {
    Guard activeGuard();

    record Guard(String catalogVersion, UUID grantPermissionId, UUID revokePermissionId) {
        public Guard {
            if (catalogVersion == null || catalogVersion.isBlank()) {
                throw new IllegalArgumentException("catalogVersion is required");
            }
            if (grantPermissionId == null || revokePermissionId == null) {
                throw new IllegalArgumentException("grant and revoke permissions are required");
            }
        }
    }
}
