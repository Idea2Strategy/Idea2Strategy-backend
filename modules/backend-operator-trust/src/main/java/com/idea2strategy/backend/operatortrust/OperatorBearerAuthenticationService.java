package com.idea2strategy.backend.operatortrust;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Shared fail-closed boundary. App adapters are responsible only for extracting the bearer value. */
public final class OperatorBearerAuthenticationService {
    private final JwtDecoder decoder;
    private final OperatorJwtAssurance assurance;
    private final JdbcOperatorIdentityResolver identities;
    private final OperatorAuthenticationEventSink events;

    public OperatorBearerAuthenticationService(
            JwtDecoder decoder,
            OperatorJwtAssurance assurance,
            JdbcOperatorIdentityResolver identities,
            OperatorAuthenticationEventSink events) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.assurance = Objects.requireNonNull(assurance, "assurance");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.events = Objects.requireNonNull(events, "events");
    }

    public Optional<OperatorRequestContext> authenticate(String rawBearerToken, UUID correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        if (rawBearerToken == null || rawBearerToken.isBlank() || rawBearerToken.length() > 65_536) {
            events.rejected(correlationId, "OPERATOR_BEARER_INVALID");
            return Optional.empty();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(rawBearerToken);
        } catch (RuntimeException rejected) {
            events.rejected(correlationId, "OPERATOR_JWT_REJECTED");
            return Optional.empty();
        }
        VerifiedOperatorJwt verified;
        try {
            verified = assurance.verifyAssurance(jwt);
        } catch (RuntimeException rejected) {
            events.rejected(correlationId, "OPERATOR_ASSURANCE_REJECTED");
            return Optional.empty();
        }
        try {
            Optional<OperatorRequestContext> resolved = identities.resolve(verified);
            if (resolved.isEmpty()) events.rejected(correlationId, "OPERATOR_MAPPING_REJECTED");
            return resolved;
        } catch (RuntimeException rejected) {
            events.rejected(correlationId, "OPERATOR_IDENTITY_DEPENDENCY_REJECTED");
            return Optional.empty();
        }
    }
}
