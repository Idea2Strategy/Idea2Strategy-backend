package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.common.CurrentSessionPrincipal;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import java.util.UUID;

public interface CustomerAccessPrincipal extends CurrentSessionPrincipal {
    UUID accountId(CustomerAccessScope accessScope);

    boolean activeSanction();
}
