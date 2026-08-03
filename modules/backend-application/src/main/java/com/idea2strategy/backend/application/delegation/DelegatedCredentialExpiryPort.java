package com.idea2strategy.backend.application.delegation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotent application boundary for delegated credential expiry. */
public interface DelegatedCredentialExpiryPort {
    enum Kind {
        CREDENTIAL,
        AUTHORIZATION
    }

    record Identity(Kind kind, UUID authorizationId, UUID credentialId, Instant expiresAt) {
        public Identity {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(authorizationId, "authorizationId");
            if (kind == Kind.CREDENTIAL) Objects.requireNonNull(credentialId, "credentialId");
            if (kind == Kind.AUTHORIZATION && credentialId != null) {
                throw new IllegalArgumentException("authorization expiry cannot identify a credential");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    enum Result {
        APPLIED,
        ALREADY_TRANSITIONED
    }

    List<Identity> findDueCredentials(int limit);

    Result expire(Identity identity, UUID correlationId);
}
