package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.LoginResult;
import com.idea2strategy.backend.application.identity.SignupResult;
import com.idea2strategy.backend.application.identity.VerificationDelivery;
import com.idea2strategy.backend.application.identity.VerificationRateLimitedException;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdentityAuthControllerTest {
    @Test
    void rejectsMissingVerificationTokenAsBadRequest() throws Exception {
        var controller = new IdentityAuthController(
                mock(EmailRegistrationService.class),
                mock(EmailAuthenticationService.class),
                mock(VerificationDeliveryPort.class),
                jwt(),
                cookies());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IdentityAuthExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signupReturnsAnImmediatelyUsableAccountWithoutSendingVerificationEmail() {
        var registration = mock(EmailRegistrationService.class);
        var authentication = mock(EmailAuthenticationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        when(registration.signup(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SignupResult(accountId, null, null));
        var controller = new IdentityAuthController(registration, authentication, delivery, jwt(), cookies());

        var response = controller.signup(
                new IdentityAuthController.SignupRequest("person@example.com", "a sufficiently long passphrase"),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().verificationRequired()).isFalse();
        assertThat(response.getBody().verificationExpiresAt()).isNull();
        verifyNoInteractions(delivery);
    }

    @Test
    void signupResponseReflectsWhetherTheServiceIssuedAVerificationToken() {
        var registration = mock(EmailRegistrationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-02T12:00:00Z");
        when(registration.signup(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SignupResult(accountId, "raw-verification-secret", expiresAt));
        var controller = new IdentityAuthController(
                registration, mock(EmailAuthenticationService.class), delivery, jwt(), cookies());

        var response = controller.signup(
                new IdentityAuthController.SignupRequest("person@example.com", "another valid passphrase!"),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().accountId()).isEqualTo(accountId);
        assertThat(response.getBody().verificationRequired()).isTrue();
        verify(delivery).send(accountId, "raw-verification-secret", expiresAt);
    }

    @Test
    void verificationLinkActivatesTheAccountAndRedirectsToLogin() throws Exception {
        var registration = mock(EmailRegistrationService.class);
        var controller = new IdentityAuthController(
                registration,
                mock(EmailAuthenticationService.class),
                mock(VerificationDeliveryPort.class),
                jwt(),
                cookies());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IdentityAuthExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/auth/verify-email").param("token", "raw-verification-secret"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?emailVerified=true"));

        verify(registration).verify(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void loginReturnsSignedAccessAndRefreshJwtsWithoutExposingTheRefreshSecret() {
        var registration = mock(EmailRegistrationService.class);
        var authentication = mock(EmailAuthenticationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        UUID loginIdentityId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-02T12:00:00Z");
        when(authentication.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LoginResult(accountId, loginIdentityId, 2, 5L,
                        familyId, "opaque-refresh-secret", expiresAt));
        var controller = new IdentityAuthController(registration, authentication, delivery, jwt(), cookies());

        var response = controller.login(
                new IdentityAuthController.LoginRequest("person@example.com", "a sufficiently long passphrase"),
                UUID.randomUUID().toString());

        var body = response.getBody();
        assertThat(body.accountId()).isEqualTo(accountId);
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.accessToken()).hasSizeGreaterThan(100).contains(".");
        assertThat(body.accessToken()).doesNotContain("opaque-refresh-secret");
        assertThat(jwt().verifyAccess(body.accessToken()).accountId()).isEqualTo(accountId);
        assertThat(response.getHeaders().getFirst(org.springframework.http.HttpHeaders.SET_COOKIE))
                .contains("i2s_refresh=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .doesNotContain("opaque-refresh-secret");
        assertThat(body.toString()).doesNotContain("a sufficiently long passphrase");
        assertThat(body.toString()).doesNotContain("opaque-refresh-secret");
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

    @Test
    void resendRateLimitReturnsTooManyRequestsWithoutSendingEmail() throws Exception {
        var registration = mock(EmailRegistrationService.class);
        var delivery = mock(VerificationDeliveryPort.class);
        UUID accountId = UUID.randomUUID();
        when(registration.resendVerification(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new VerificationRateLimitedException());
        var controller = new IdentityAuthController(
                registration, mock(EmailAuthenticationService.class), delivery, jwt(), cookies());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IdentityAuthExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\"}"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(delivery);
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

    private static RefreshTokenCookie cookies() {
        return new RefreshTokenCookie(
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC),
                true,
                "Strict");
    }
}
