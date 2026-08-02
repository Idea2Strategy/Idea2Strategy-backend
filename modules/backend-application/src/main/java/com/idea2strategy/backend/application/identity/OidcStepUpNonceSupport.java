package com.idea2strategy.backend.application.identity;

public interface OidcStepUpNonceSupport {
    IssuedOidcStepUpNonce issue();

    String digest(String rawNonce);
}
