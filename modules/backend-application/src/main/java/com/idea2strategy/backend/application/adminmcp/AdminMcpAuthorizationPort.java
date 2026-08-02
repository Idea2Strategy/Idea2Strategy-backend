package com.idea2strategy.backend.application.adminmcp;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface AdminMcpAuthorizationPort {
    Decision authorize(
            OperatorRequestContext requestContext,
            UUID requiredPermissionId,
            String targetDomain,
            Instant evaluatedAt);

    record Decision(boolean granted, String code, String rbacCatalogVersion) {
        public Decision {
            Objects.requireNonNull(code, "code");
        }

        public static Decision granted(String rbacCatalogVersion) {
            return new Decision(true, "AUTHORIZED", Objects.requireNonNull(rbacCatalogVersion));
        }

        public static Decision rejected(String code, String rbacCatalogVersion) {
            return new Decision(false, code, rbacCatalogVersion);
        }
    }
}
