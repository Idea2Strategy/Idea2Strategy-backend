package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.identity.DeviceAuthorizationOutcome;
import com.idea2strategy.backend.application.identity.DeviceAuthorizationService;
import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing a command-line client in through the browser.
 *
 * <p>The CLI never sees a password. It asks for a pair of codes, shows the short one, and polls;
 * the customer approves in a browser session that already holds their credential. That matters most
 * when something else is driving the CLI — an agent told to "set it up" would otherwise have to be
 * handed the password itself.
 */
@RestController
@RequestMapping("/api/v1/auth/device")
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
public class DeviceAuthorizationController {
    private final DeviceAuthorizationService devices;
    private final EmailAuthenticationService authentication;
    private final IdentityAuthController tokens;
    private final CurrentPrincipal principal;
    private final String verificationUri;

    public DeviceAuthorizationController(
            DeviceAuthorizationService devices,
            EmailAuthenticationService authentication,
            IdentityAuthController tokens,
            CurrentPrincipal principal,
            @org.springframework.beans.factory.annotation.Value(
                    "${identity.device-authorization.verification-uri:https://ideatostrategy.com/cli-auth}")
                    String verificationUri) {
        this.devices = devices;
        this.authentication = authentication;
        this.tokens = tokens;
        this.principal = principal;
        this.verificationUri = verificationUri;
    }

    /** Unauthenticated: this is what a client calls before it has any credential at all. */
    @PostMapping("/authorize")
    public ResponseEntity<DeviceAuthorizationResponse> authorize(
            @RequestBody(required = false) AuthorizeRequest request) {
        String label = request == null || request.clientLabel() == null || request.clientLabel().isBlank()
                ? "idea2strategy-cli"
                : request.clientLabel().trim();
        var grant = devices.request(label);
        return ResponseEntity.status(HttpStatus.CREATED).body(new DeviceAuthorizationResponse(
                grant.deviceCode(),
                grant.userCode(),
                verificationUri,
                verificationUri + "?code=" + grant.userCode(),
                grant.expiresAt(),
                grant.pollIntervalSeconds()));
    }

    /** The account comes from the browser session, never from the body. */
    @PostMapping("/approve")
    public ResponseEntity<Void> approve(@RequestBody UserCodeRequest request) {
        devices.approve(request.userCode(), principal.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/deny")
    public ResponseEntity<Void> deny(@RequestBody UserCodeRequest request) {
        devices.deny(request.userCode());
        return ResponseEntity.noContent().build();
    }

    /**
     * Unauthenticated, and answers 202 while pending so a polling client can tell "not yet" from a
     * refusal without reading a body it might not parse.
     */
    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody DeviceCodeRequest request) {
        DeviceAuthorizationOutcome outcome = devices.collect(request.deviceCode());
        return switch (outcome.status()) {
            case APPROVED -> ResponseEntity.ok(tokens.tokenResponse(
                            authentication.completeApprovedDeviceLogin(
                                    outcome.accountId().orElseThrow(), UUID.randomUUID()))
                    .getBody());
            case PENDING -> ResponseEntity.accepted().body(new PendingResponse("authorization_pending"));
            case DENIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(new PendingResponse("access_denied"));
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).body(new PendingResponse("expired_token"));
            case UNKNOWN -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new PendingResponse("invalid_device_code"));
        };
    }

    public record AuthorizeRequest(String clientLabel) {}

    public record UserCodeRequest(String userCode) {}

    public record DeviceCodeRequest(String deviceCode) {
        @Override
        public String toString() {
            return "DeviceCodeRequest[deviceCode=REDACTED]";
        }
    }

    public record DeviceAuthorizationResponse(
            String deviceCode,
            String userCode,
            String verificationUri,
            String verificationUriComplete,
            Instant expiresAt,
            short intervalSeconds) {
        @Override
        public String toString() {
            return "DeviceAuthorizationResponse[codes=REDACTED]";
        }
    }

    public record PendingResponse(String error) {}
}
