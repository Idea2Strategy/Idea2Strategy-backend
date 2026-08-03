package com.idea2strategy.backend.operatortrust;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Builds the operator-only decoder. It cannot be relaxed to customer or service credentials. */
public final class OperatorJwtDecoderFactory {
    private OperatorJwtDecoderFactory() {}

    public static JwtDecoder create(OperatorTrustConfiguration configuration, Clock clock) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(clock, "clock");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(configuration.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(configuration.clockSkew());
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(configuration.issuer()),
                operatorClaims(configuration, clock)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> operatorClaims(
            OperatorTrustConfiguration configuration, Clock clock) {
        return jwt -> {
            Instant now = clock.instant();
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            List<String> audience = jwt.getAudience();
            Object keyId = jwt.getHeaders().get("kid");
            boolean valid = configuration.issuer().equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                    && keyId instanceof String value && !value.isBlank()
                    && audience != null && audience.size() == 1
                    && configuration.audience().equals(audience.getFirst())
                    && issuedAt != null && expiresAt != null
                    && !issuedAt.isAfter(now.plus(configuration.clockSkew()))
                    && !issuedAt.isBefore(now.minus(configuration.maximumTokenAge()).minus(configuration.clockSkew()))
                    && expiresAt.isAfter(now.minus(configuration.clockSkew()));
            return valid ? OAuth2TokenValidatorResult.success() : rejected();
        };
    }

    private static OAuth2TokenValidatorResult rejected() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "operator JWT claims rejected", null));
    }
}
