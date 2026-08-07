package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.LoginResult;
import com.idea2strategy.backend.application.identity.SignupResult;
import com.idea2strategy.backend.application.identity.VerificationDelivery;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAuthControllerTest {
    @Test
    void signupDeliversVerificationSecretWithoutReturningItInTheApiBody() {
        var registration = mock(EmailRegistrationService.class);
        var authentication = mock(EmailAuthenticationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-02T12:00:00Z");
        when(registration.signup(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SignupResult(accountId, "raw-verification-secret", expiresAt));
        var controller = new IdentityAuthController(registration, authentication, delivery, jwt(), cookies());

        var response = controller.signup(
                new IdentityAuthController.SignupRequest("person@example.com", "a sufficiently long passphrase"),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().toString()).doesNotContain("raw-verification-secret");
        verify(delivery).send("person@example.com", "raw-verification-secret", expiresAt);
    }

    @Test
    void loginReturnsSignedAccessAndRefreshJwtsWithoutExposingTheOpaqueSessionToken() {
        var registration = mock(EmailRegistrationService.class);
        var authentication = mock(EmailAuthenticationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-02T12:00:00Z");
        when(authentication.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LoginResult(accountId, sessionId, "opaque-session-token", expiresAt));
        var controller = new IdentityAuthController(registration, authentication, delivery, jwt(), cookies());

        var response = controller.login(
                new IdentityAuthController.LoginRequest("person@example.com", "a sufficiently long passphrase", "Chrome"),
                UUID.randomUUID().toString());

        var body = response.getBody();
        assertThat(body.accountId()).isEqualTo(accountId);
        assertThat(body.sessionId()).isEqualTo(sessionId);
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.accessToken()).hasSizeGreaterThan(100).contains(".");
        assertThat(body.accessToken()).doesNotContain("opaque-session-token");
        assertThat(jwt().verifyAccess(body.accessToken()).accountId()).isEqualTo(accountId);
        assertThat(response.getHeaders().getFirst(org.springframework.http.HttpHeaders.SET_COOKIE))
                .contains("i2s_refresh=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .doesNotContain("opaque-session-token");
        assertThat(body.toString()).doesNotContain("a sufficiently long passphrase");
        assertThat(body.toString()).doesNotContain("opaque-session-token");
    }

    @Test
    void resendDeliversTheReplacementWithoutReturningItsSecret() {
        var registration = mock(EmailRegistrationService.class);
        var authentication = mock(EmailAuthenticationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-02T12:00:00Z");
        when(registration.resendVerification(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new VerificationDelivery("replacement-secret", expiresAt));
        var controller = new IdentityAuthController(registration, authentication, delivery, jwt(), cookies());

        var response = controller.resendVerification(
                new IdentityAuthController.ResendVerificationRequest(accountId),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().toString()).doesNotContain("replacement-secret");
        verify(delivery).send(accountId, "replacement-secret", expiresAt);
    }

    private static CustomerJwtCodec jwt() {
        return new CustomerJwtCodec(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC),
                "https://ideatostrategy.com",
                "idea2strategy-api",
                "idea2strategy-refresh",
                Duration.ofMinutes(5));
    }

    private static RefreshSessionCookie cookies() {
        return new RefreshSessionCookie(
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC),
                true,
                "Strict");
    }
}
