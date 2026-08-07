package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedCustomer(UUID accountId, boolean activeSanction) {
    public AuthenticatedCustomer {
        Objects.requireNonNull(accountId, "accountId");
    }
}
