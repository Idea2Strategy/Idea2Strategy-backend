package com.idea2strategy.backend.operatortrust;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads assurance only after signature and standard claim validation succeeded. */
public final class OperatorJwtAssurance {
    private final OperatorTrustConfiguration configuration;
    private final Clock clock;

    public OperatorJwtAssurance(OperatorTrustConfiguration configuration, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public VerifiedOperatorJwt verifyAssurance(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        if (!configuration.issuer().equals(issuer)
                || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("OPERATOR_JWT_INVALID");
        }
        Instant authenticatedAt = instant(jwt.getClaim("auth_time"));
        Instant issuedAt = jwt.getIssuedAt();
        boolean approvedAssurance = approvedAcr(jwt.getClaim("acr"))
                || approvedAmr(jwt.getClaim("amr"))
                || approvedNamespacedMfa(jwt);
        Instant now = clock.instant();
        boolean fresh = authenticatedAt != null
                && issuedAt != null
                && !authenticatedAt.isAfter(issuedAt)
                && !authenticatedAt.isAfter(now)
                && !authenticatedAt.isBefore(now.minus(configuration.maximumMfaAge()))
                && approvedAssurance;
        return new VerifiedOperatorJwt(issuer, jwt.getSubject(), authenticatedAt, fresh);
    }

    private boolean approvedAcr(Object claim) {
        return claim instanceof String value && configuration.allowedAcrValues().contains(value);
    }

    private boolean approvedAmr(Object claim) {
        if (!(claim instanceof Collection<?> values)) return false;
        return values.stream().filter(String.class::isInstance).map(String.class::cast)
                .anyMatch(configuration.allowedAmrValues()::contains);
    }

    private boolean approvedNamespacedMfa(Jwt jwt) {
        String claimName = configuration.mfaClaimName();
        if (claimName == null) return false;
        Object claim = jwt.getClaim(claimName);
        return claim instanceof String value
                && configuration.allowedMfaClaimValues().contains(value);
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof Number number) {
            try { return Instant.ofEpochSecond(number.longValue()); }
            catch (RuntimeException ignored) { return null; }
        }
        return null;
    }
}
