package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.AccountLifecycleCommand;
import com.idea2strategy.backend.application.identity.AccountLifecycleResult;
import com.idea2strategy.backend.application.identity.AccountLifecycleRejectedException;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import com.idea2strategy.backend.application.identity.LifecyclePasswordStepUpService;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
@RequestMapping("/api/v1/account")
@ConditionalOnBean({AccountLifecycleService.class, LifecyclePasswordStepUpService.class})
public class IdentityAccountLifecycleController {
    private static final String REQUEST_WITHDRAWAL = "REQUEST_WITHDRAWAL";
    private static final String CANCEL_WITHDRAWAL = "CANCEL_WITHDRAWAL";

    private final AccountLifecycleService lifecycle;
    private final LifecyclePasswordStepUpService stepUp;

    public IdentityAccountLifecycleController(
            AccountLifecycleService lifecycle,
            LifecyclePasswordStepUpService stepUp) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.stepUp = Objects.requireNonNull(stepUp, "stepUp");
    }

    @PostMapping("/withdrawal-requests")
    public ResponseEntity<LifecycleResponse> requestWithdrawal(
            @RequestBody PasswordStepUpRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        try {
            var authenticated = stepUp.authenticate(request.email(), request.password(), correlation);
            var command = new AccountLifecycleCommand(
                    authenticated.accountId(),
                    idempotencyKey,
                    requestHash(authenticated.accountId(), REQUEST_WITHDRAWAL),
                    correlation,
                    authenticated.proof());
            return ResponseEntity.accepted().body(LifecycleResponse.from(lifecycle.requestWithdrawal(command)));
        } catch (AuthenticationRejectedException exception) {
            throw new LifecycleRequestRejectedException("STEP_UP_REQUIRED", correlation);
        } catch (AccountLifecycleRejectedException exception) {
            throw new LifecycleRequestRejectedException(exception.code(), correlation);
        }
    }

    @PostMapping("/withdrawal-cancellations")
    public LifecycleResponse cancelWithdrawal(
            @RequestBody PasswordStepUpRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        try {
            var authenticated = stepUp.authenticate(request.email(), request.password(), correlation);
            var command = new AccountLifecycleCommand(
                    authenticated.accountId(),
                    idempotencyKey,
                    requestHash(authenticated.accountId(), CANCEL_WITHDRAWAL),
                    correlation,
                    authenticated.proof());
            return LifecycleResponse.from(lifecycle.cancelWithdrawal(command));
        } catch (AuthenticationRejectedException exception) {
            throw new LifecycleRequestRejectedException("STEP_UP_REQUIRED", correlation);
        } catch (AccountLifecycleRejectedException exception) {
            throw new LifecycleRequestRejectedException(exception.code(), correlation);
        }
    }

    static String requestHash(UUID accountId, String commandType) {
        String canonical = "account-lifecycle:v1\naccountId=" + accountId + "\ncommandType=" + commandType;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record PasswordStepUpRequest(String email, String password) {
        public PasswordStepUpRequest {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "PasswordStepUpRequest[credentials=REDACTED]";
        }
    }

    public record LifecycleResponse(
            UUID accountId,
            AccountLifecycleStatus status,
            long version,
            Instant withdrawalRequestedAt,
            Instant cancellationDeadlineAt,
            boolean applied) {
        private static LifecycleResponse from(AccountLifecycleResult result) {
            return new LifecycleResponse(
                    result.accountId(),
                    result.status(),
                    result.version(),
                    result.withdrawalRequestedAt(),
                    result.cancellationDeadlineAt(),
                    result.applied());
        }
    }
}
