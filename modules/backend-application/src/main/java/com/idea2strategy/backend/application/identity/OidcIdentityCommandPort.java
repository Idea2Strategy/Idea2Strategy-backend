package com.idea2strategy.backend.application.identity;

public interface OidcIdentityCommandPort {
    void createPendingLink(PendingOidcLink link);

    long activatePendingLink(ActivateOidcLink command);
}
