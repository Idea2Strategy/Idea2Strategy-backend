package com.idea2strategy.backend.api.identity;

import java.util.UUID;

@FunctionalInterface
public interface IdentityEmailAddressResolver {
    String requireEmail(UUID accountId);
}
