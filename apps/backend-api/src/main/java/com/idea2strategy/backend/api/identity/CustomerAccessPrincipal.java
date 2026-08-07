package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import java.util.UUID;

public interface CustomerAccessPrincipal extends CurrentPrincipal {
    UUID accountId(CustomerAccessScope accessScope);

    boolean activeSanction();
}
