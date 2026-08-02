package com.idea2strategy.backend.application.accountsanction;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public interface AccountSanctionAuthorizationPort {
    Decision authorize(OperatorRequestContext context, UUID requiredPermissionId, Instant evaluatedAt);

    record Decision(
            boolean authorized,
            String code,
            String catalogVersion,
            Set<UUID> roleIds,
            Set<UUID> permissionIds,
            boolean activeOperator,
            boolean mfaSatisfied) {

        public Decision {
            Objects.requireNonNull(code, "code");
            roleIds = Set.copyOf(roleIds);
            permissionIds = Set.copyOf(permissionIds);
        }

        static Decision system() {
            return new Decision(true, "SYSTEM_EXPIRY", null, Set.of(), Set.of(), true, true);
        }
    }
}
