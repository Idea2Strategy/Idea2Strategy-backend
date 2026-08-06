package com.idea2strategy.backend.operatortrust;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class OperatorJwtAssuranceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T04:00:00Z");
    private final OperatorJwtAssurance assurance = new OperatorJwtAssurance(
            OperatorTrustTestFixtures.configuration(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsApprovedSignedAcrOrAmrOnlyWithFreshAuthenticationTime() {
        assertThat(assurance.verifyAssurance(jwt(Map.of(
                "acr", "urn:mfa", "auth_time", NOW.minusSeconds(60).getEpochSecond()))).currentMfa()).isTrue();
        assertThat(assurance.verifyAssurance(jwt(Map.of(
                "amr", List.of("pwd", "otp"), "auth_time", NOW.minusSeconds(60)))).currentMfa()).isTrue();
    }

    @Test
    void staleMissingFutureOrUnapprovedAssuranceNeverClaimsMfa() {
        assertThat(assurance.verifyAssurance(jwt(Map.of(
                "amr", List.of("mfa"), "auth_time", NOW.minusSeconds(601).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(assurance.verifyAssurance(jwt(Map.of("amr", List.of("mfa")))).currentMfa()).isFalse();
        assertThat(assurance.verifyAssurance(jwt(Map.of(
                "amr", List.of("mfa"), "auth_time", NOW.plusSeconds(1).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(assurance.verifyAssurance(jwt(Map.of(
                "amr", List.of("mfa"), "auth_time", NOW.plusSeconds(31).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(assurance.verifyAssurance(jwt(Map.of(
                "amr", List.of("mfa"), "auth_time", NOW.minusSeconds(30).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(assurance.verifyAssurance(jwt(Map.of(
                "amr", List.of("pwd"), "auth_time", NOW.minusSeconds(1).getEpochSecond()))).currentMfa()).isFalse();
    }

    @Test
    void acceptsOnlyTheConfiguredNamespacedClaimForAForcedMfaProvider() {
        var cognito = new OperatorJwtAssurance(
                OperatorTrustTestFixtures.cognitoConfiguration(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "https://ideatostrategy.com/claims/mfa", "cognito:mfa-required",
                "auth_time", NOW.minusSeconds(60).getEpochSecond()))).currentMfa()).isTrue();
        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "https://ideatostrategy.com/claims/mfa", "unapproved",
                "auth_time", NOW.minusSeconds(60).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "mfa", "cognito:mfa-required",
                "auth_time", NOW.minusSeconds(60).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "https://ideatostrategy.com/claims/mfa", List.of("cognito:mfa-required"),
                "auth_time", NOW.minusSeconds(60).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "https://ideatostrategy.com/claims/mfa", List.of("cognito:mfa-required", "other"),
                "auth_time", NOW.minusSeconds(60).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "https://ideatostrategy.com/claims/mfa", "cognito:mfa-required",
                "auth_time", NOW.minusSeconds(601).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "https://ideatostrategy.com/claims/mfa", "cognito:mfa-required",
                "auth_time", NOW.plusSeconds(1).getEpochSecond()))).currentMfa()).isFalse();
        assertThat(cognito.verifyAssurance(jwt(Map.of(
                "auth_time", NOW.minusSeconds(60).getEpochSecond()))).currentMfa()).isFalse();
    }

    static Jwt jwt(Map<String, Object> extraClaims) {
        var builder = Jwt.withTokenValue("verified-token")
                .header("alg", "RS256")
                .issuer("https://operator.example")
                .subject("operator-subject")
                .audience(List.of("operator-api"))
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(60));
        extraClaims.forEach(builder::claim);
        return builder.build();
    }
}
