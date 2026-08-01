package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface OidcSubjectProtector {
    ProtectedOidcSubject protect(VerifiedOidcPrincipal principal);
}
