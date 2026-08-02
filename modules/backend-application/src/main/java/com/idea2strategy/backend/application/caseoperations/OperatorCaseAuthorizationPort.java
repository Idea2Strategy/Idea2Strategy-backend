package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface OperatorCaseAuthorizationPort {
    Decision authorize(
            OperatorRequestContext context,
            UUID requiredPermissionId,
            UserCaseType caseType,
            String action,
            Instant evaluatedAt);

    record Decision(boolean granted, String code, String rbacCatalogVersion) {
        public Decision {
            Objects.requireNonNull(code, "code");
        }

        public static Decision granted(String catalogVersion) {
            return new Decision(true, "AUTHORIZED", Objects.requireNonNull(catalogVersion));
        }

        public static Decision rejected(String code, String catalogVersion) {
            return new Decision(false, code, catalogVersion);
        }
    }
}
