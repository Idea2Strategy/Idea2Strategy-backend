package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.PasswordRecoveryService;
import com.idea2strategy.backend.application.identity.RequestPasswordResetCommand;
import com.idea2strategy.backend.application.identity.RecoverWithCodeCommand;
import com.idea2strategy.backend.application.identity.ResetPasswordCommand;
import com.idea2strategy.backend.application.identity.IssuedRecoveryCodes;
import com.idea2strategy.backend.application.identity.RefreshTokenService;
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
@ConditionalOnBean({PasswordRecoveryService.class, PasswordResetDeliveryPort.class, RefreshTokenService.class, HmacRefreshTokenSecrets.class})
public class IdentityRecoveryController {
    private final PasswordRecoveryService recovery;
    private final PasswordResetDeliveryPort delivery;
    private final CustomerAccessPrincipal principal;

    public IdentityRecoveryController(
            PasswordRecoveryService recovery,
            PasswordResetDeliveryPort delivery,
            CustomerAccessPrincipal principal) {
        this.recovery = recovery;
        this.delivery = delivery;
        this.principal = principal;
    }

    @PostMapping("/password-reset-requests")
    public ResponseEntity<PasswordResetRequestResponse> requestPasswordReset(
            @RequestBody PasswordResetRequestRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Client-IP-Prefix", required = false) String requestIpPrefix) {
        recovery.requestPasswordReset(new RequestPasswordResetCommand(
                        request.email(), correlation(correlationId), requestIpPrefix))
                .ifPresent(value -> delivery.send(value.accountId(), value.rawToken(), value.expiresAt()));
        return ResponseEntity.accepted().body(new PasswordResetRequestResponse(true));
    }

    @PostMapping("/password-resets")
    public ResponseEntity<Void> resetPassword(
            @RequestBody PasswordResetRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        recovery.resetPassword(new ResetPasswordCommand(
                request.resetToken(), request.newPassword(), correlation(correlationId)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recovery-codes")
    public IssuedRecoveryCodes issueRecoveryCodes(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        return recovery.issueRecoveryCodes(principal.accountId(), correlation);
    }

    @PostMapping("/recovery-code-resets")
    public ResponseEntity<Void> recoverWithCode(
            @RequestBody RecoveryCodeResetRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        recovery.recoverWithCode(new RecoverWithCodeCommand(
                request.email(), request.recoveryCode(), request.newPassword(), correlation(correlationId)));
        return ResponseEntity.noContent().build();
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record PasswordResetRequestRequest(String email) {
        public PasswordResetRequestRequest {
            Objects.requireNonNull(email, "email");
        }

        @Override
        public String toString() {
            return "PasswordResetRequestRequest[email=REDACTED]";
        }
    }

    public record PasswordResetRequestResponse(boolean accepted) {}

    public record PasswordResetRequest(String resetToken, String newPassword) {
        public PasswordResetRequest {
            Objects.requireNonNull(resetToken, "resetToken");
            Objects.requireNonNull(newPassword, "newPassword");
        }

        @Override
        public String toString() {
            return "PasswordResetRequest[credentials=REDACTED]";
        }
    }

    public record RecoveryCodeResetRequest(String email, String recoveryCode, String newPassword) {
        public RecoveryCodeResetRequest {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(recoveryCode, "recoveryCode");
            Objects.requireNonNull(newPassword, "newPassword");
        }

        @Override
        public String toString() {
            return "RecoveryCodeResetRequest[credentials=REDACTED]";
        }
    }
}
