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
        var controller = new IdentityAuthController(registration, authentication, delivery);

        var response = controller.signup(
                new IdentityAuthController.SignupRequest("person@example.com", "a sufficiently long passphrase"),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().toString()).doesNotContain("raw-verification-secret");
        verify(delivery).send("person@example.com", "raw-verification-secret", expiresAt);
    }

    @Test
    void loginReturnsOnlyTheNewOpaqueSessionToken() {
        var registration = mock(EmailRegistrationService.class);
        var authentication = mock(EmailAuthenticationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-02T00:00:00Z");
        when(authentication.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LoginResult(accountId, sessionId, "opaque-session-token", expiresAt));
        var controller = new IdentityAuthController(registration, authentication, delivery);

        var response = controller.login(
                new IdentityAuthController.LoginRequest("person@example.com", "a sufficiently long passphrase", "Chrome"),
                UUID.randomUUID().toString());

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.sessionToken()).isEqualTo("opaque-session-token");
        assertThat(response.toString()).doesNotContain("a sufficiently long passphrase");
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
        var controller = new IdentityAuthController(registration, authentication, delivery);

        var response = controller.resendVerification(
                new IdentityAuthController.ResendVerificationRequest(accountId),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().toString()).doesNotContain("replacement-secret");
        verify(delivery).send(accountId, "replacement-secret", expiresAt);
    }
}
