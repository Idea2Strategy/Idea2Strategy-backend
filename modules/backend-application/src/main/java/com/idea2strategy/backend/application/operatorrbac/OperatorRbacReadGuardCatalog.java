package com.idea2strategy.backend.application.operatorrbac;

import java.util.Objects;
import java.util.UUID;

public interface OperatorRbacReadGuardCatalog {
    Guard activeGuard();

    record Guard(
            String expectedCatalogVersion,
            UUID catalogReadPermissionId,
            boolean catalogReadMfaRequired,
            UUID assignmentReadPermissionId,
            boolean assignmentReadMfaRequired) {
        public Guard {
            Objects.requireNonNull(expectedCatalogVersion, "expectedCatalogVersion");
            Objects.requireNonNull(catalogReadPermissionId, "catalogReadPermissionId");
            Objects.requireNonNull(assignmentReadPermissionId, "assignmentReadPermissionId");
            if (expectedCatalogVersion.isBlank()
                    || catalogReadPermissionId.equals(assignmentReadPermissionId)) {
                throw new IllegalArgumentException("OPERATOR_RBAC_READ_GUARD_INVALID");
            }
        }
    }
}
