package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.util.UUID;

public record TestCustomerAccessPrincipal(UUID accountId) implements CurrentPrincipal {}
