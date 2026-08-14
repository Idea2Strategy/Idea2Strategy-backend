package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.LoginCommand;
import com.idea2strategy.backend.application.identity.LoginResult;
import com.idea2strategy.backend.application.identity.ResendVerificationCommand;
import com.idea2strategy.backend.application.identity.SignupCommand;
import com.idea2strategy.backend.application.identity.VerifyEmailCommand;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnBean({EmailRegistrationService.class, EmailAuthenticationService.class, VerificationDeliveryPort.class})
public class IdentityAuthController {
    private final EmailRegistrationService registrationService;
    private final EmailAuthenticationService authenticationService;
    private final VerificationDeliveryPort verificationDelivery;
    private final CustomerJwtCodec jwt;
    private final RefreshTokenCookie refreshCookie;

    public IdentityAuthController(
            EmailRegistrationService registrationService,
            EmailAuthenticationService authenticationService,
            VerificationDeliveryPort verificationDelivery,
            CustomerJwtCodec jwt,
            RefreshTokenCookie refreshCookie) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.verificationDelivery = verificationDelivery;
        this.jwt = jwt;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(
            @RequestBody SignupRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Client-IP-Prefix", required = false) String requestIpPrefix) {
        UUID correlation = correlation(correlationId);
        var result = registrationService.signup(
                new SignupCommand(request.email(), request.password(), correlation, requestIpPrefix));
        if (result.verificationToken() != null) {
            verificationDelivery.send(result.accountId(), result.verificationToken(), result.expiresAt());
        }
        return ResponseEntity.accepted().body(new SignupResponse(
                result.accountId(), result.verificationToken() != null, result.expiresAt()));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmailLink(
            @RequestParam("token") String verificationToken,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (verificationToken == null || verificationToken.isBlank()) {
            throw new IllegalArgumentException("Verification token is required");
        }
        registrationService.verify(new VerifyEmailCommand(verificationToken, correlation(correlationId)));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/login?emailVerified=true"))
                .build();
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
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        var result = authenticationService.login(new LoginCommand(
                request.email(), request.password(), correlation(correlationId)));
        return tokenResponse(result);
    }

    ResponseEntity<LoginResponse> tokenResponse(LoginResult result) {
        String refreshJwt = jwt.issueRefresh(
                result.accountId(),
                result.refreshTokenFamilyId(),
                result.loginIdentityId(),
                result.authEpoch(),
                result.credentialVersion(),
                result.refreshTokenSecret(),
                result.expiresAt());
        var body = new LoginResponse(
                result.accountId(),
                "Bearer",
                jwt.issueAccess(
                        result.accountId(), result.loginIdentityId(), result.authEpoch(), result.credentialVersion()),
                jwt.accessExpiresAt(),
                result.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(refreshJwt, result.expiresAt()).toString())
                .body(body);
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
        public VerifyEmailRequest {
            if (verificationToken == null || verificationToken.isBlank()) {
                throw new IllegalArgumentException("Verification token is required");
            }
        }

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

    public record LoginRequest(String email, String password) {
        public LoginRequest {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "LoginRequest[credentials=REDACTED]";
        }
    }

    public record LoginResponse(
            UUID accountId,
            String tokenType,
            String accessToken,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        @Override
        public String toString() {
            return "LoginResponse[accountId=" + accountId
                    + ",tokenType=" + tokenType + ",accessToken=REDACTED"
                    + ",accessExpiresAt=" + accessExpiresAt + ",refreshExpiresAt=" + refreshExpiresAt + "]";
        }
    }
}
