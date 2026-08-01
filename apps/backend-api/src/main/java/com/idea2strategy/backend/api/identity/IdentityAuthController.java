package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.LoginCommand;
import com.idea2strategy.backend.application.identity.ResendVerificationCommand;
import com.idea2strategy.backend.application.identity.SignupCommand;
import com.idea2strategy.backend.application.identity.VerifyEmailCommand;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnBean({EmailRegistrationService.class, EmailAuthenticationService.class, VerificationDeliveryPort.class})
public class IdentityAuthController {
    private final EmailRegistrationService registrationService;
    private final EmailAuthenticationService authenticationService;
    private final VerificationDeliveryPort verificationDelivery;

    public IdentityAuthController(
            EmailRegistrationService registrationService,
            EmailAuthenticationService authenticationService,
            VerificationDeliveryPort verificationDelivery) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.verificationDelivery = verificationDelivery;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(
            @RequestBody SignupRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Client-IP-Prefix", required = false) String requestIpPrefix) {
        UUID correlation = correlation(correlationId);
        var result = registrationService.signup(
                new SignupCommand(request.email(), request.password(), correlation, requestIpPrefix));
        verificationDelivery.send(request.email(), result.verificationToken(), result.expiresAt());
        return ResponseEntity.accepted().body(new SignupResponse(result.accountId(), true, result.expiresAt()));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(
            @RequestBody VerifyEmailRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        registrationService.verify(new VerifyEmailCommand(request.verificationToken(), correlation(correlationId)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ResendVerificationResponse> resendVerification(
            @RequestBody ResendVerificationRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Client-IP-Prefix", required = false) String requestIpPrefix) {
        var delivery = registrationService.resendVerification(new ResendVerificationCommand(
                request.accountId(), correlation(correlationId), requestIpPrefix));
        verificationDelivery.send(request.accountId(), delivery.verificationToken(), delivery.expiresAt());
        return ResponseEntity.accepted().body(new ResendVerificationResponse(true, delivery.expiresAt()));
    }

    @PostMapping("/login")
    public LoginResponse login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        var result = authenticationService.login(new LoginCommand(
                request.email(), request.password(), request.deviceLabel(), correlation(correlationId)));
        return new LoginResponse(
                result.accountId(), result.sessionId(), result.sessionToken(), result.expiresAt());
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record SignupRequest(String email, String password) {
        public SignupRequest {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "SignupRequest[credentials=REDACTED]";
        }
    }

    public record SignupResponse(UUID accountId, boolean verificationRequired, Instant verificationExpiresAt) {}

    public record VerifyEmailRequest(String verificationToken) {
        @Override
        public String toString() {
            return "VerifyEmailRequest[token=REDACTED]";
        }
    }

    public record ResendVerificationRequest(UUID accountId) {
        public ResendVerificationRequest {
            Objects.requireNonNull(accountId, "accountId");
        }
    }

    public record ResendVerificationResponse(boolean verificationRequired, Instant verificationExpiresAt) {}

    public record LoginRequest(String email, String password, String deviceLabel) {
        public LoginRequest {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "LoginRequest[credentials=REDACTED, deviceLabel=" + deviceLabel + "]";
        }
    }

    public record LoginResponse(UUID accountId, UUID sessionId, String sessionToken, Instant expiresAt) {}
}
