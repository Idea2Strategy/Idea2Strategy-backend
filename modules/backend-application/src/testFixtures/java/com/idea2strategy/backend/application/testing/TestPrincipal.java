package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.util.Objects;
import java.util.UUID;

public record TestPrincipal(UUID accountId) implements CurrentPrincipal {
    public TestPrincipal {
        Objects.requireNonNull(accountId, "accountId");
    }
}
