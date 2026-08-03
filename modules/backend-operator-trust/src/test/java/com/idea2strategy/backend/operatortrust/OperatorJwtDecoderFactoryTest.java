package com.idea2strategy.backend.operatortrust;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class OperatorJwtDecoderFactoryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T04:00:00Z");
    private final OperatorTrustConfiguration configuration = OperatorTrustTestFixtures.configuration();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void requiresExactIssuerSingleAudienceSubjectExpiryAndBoundedIssuedAt() {
        assertThat(validate(valid())).isTrue();
        assertThat(validate(valid().issuer("https://customer.example").build())).isFalse();
        assertThat(validate(valid().headers(headers -> headers.remove("kid")).build())).isFalse();
        assertThat(validate(valid().audience(List.of("operator-api", "other")).build())).isFalse();
        assertThat(validate(valid().subject(" ").build())).isFalse();
        assertThat(validate(valid().issuedAt(NOW.minusSeconds(331)).build())).isFalse();
        assertThat(validate(valid().issuedAt(NOW.plusSeconds(31)).build())).isFalse();
    }

    private boolean validate(Jwt.Builder builder) {
        return validate(builder.build());
    }

    private boolean validate(Jwt jwt) {
        return !OperatorJwtDecoderFactory.operatorClaims(configuration, clock)
                .validate(jwt).hasErrors();
    }

    private Jwt.Builder valid() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .header("kid", "operator-key-1")
                .issuer("https://operator.example")
                .subject("subject")
                .audience(List.of("operator-api"))
                .issuedAt(NOW.minusSeconds(60))
                .notBefore(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(60));
    }
}
