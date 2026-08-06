package com.idea2strategy.backend.application.identity;

public interface OidcIdentityCommandPort {
    default void createActiveRegistration(PendingOidcRegistration registration) {
        throw new UnsupportedOperationException("OIDC registration is not configured");
    }

    void createPendingLink(PendingOidcLink link);

    long activatePendingLink(ActivateOidcLink command);
}
