package com.idea2strategy.backend.application.identity;

import java.util.Optional;

public interface OidcIdentityQueryPort {
    Optional<OidcProvider> findProvider(String providerCode);

    Optional<OidcLoginAccount> findActiveLogin(short providerId, String subjectHmac);

    default boolean subjectExists(short providerId, String subjectHmac) {
        return findActiveLogin(providerId, subjectHmac).isPresent();
    }
}
