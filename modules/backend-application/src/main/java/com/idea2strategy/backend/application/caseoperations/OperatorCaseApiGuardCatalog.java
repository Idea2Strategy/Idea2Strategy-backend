package com.idea2strategy.backend.application.caseoperations;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface OperatorCaseApiGuardCatalog {
    Guard activeGuard();

    record Guard(
            UUID queuePermissionId,
            UUID detailPermissionId,
            Map<OperatorCaseCommand.Action, UUID> commandPermissionIds) {
        public Guard {
            Objects.requireNonNull(queuePermissionId, "queuePermissionId");
            Objects.requireNonNull(detailPermissionId, "detailPermissionId");
            commandPermissionIds = Map.copyOf(commandPermissionIds);
        }

        public UUID permissionFor(OperatorCaseCommand.Action action) {
            UUID permission = commandPermissionIds.get(action);
            if (permission == null) {
                throw new IllegalStateException("OPERATOR_CASE_GUARD_NOT_CONFIGURED");
            }
            return permission;
        }
    }
}
