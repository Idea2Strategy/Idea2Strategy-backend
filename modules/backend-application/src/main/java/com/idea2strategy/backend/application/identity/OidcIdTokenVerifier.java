package com.idea2strategy.backend.application.identity;

public interface OidcIdTokenVerifier {
    VerifiedOidcIdToken verify(OidcIdTokenVerificationRequest request);
}
