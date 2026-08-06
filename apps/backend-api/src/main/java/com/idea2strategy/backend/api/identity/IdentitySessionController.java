package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.RotatedSession;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import com.idea2strategy.backend.application.identity.SessionView;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/sessions")
@ConditionalOnBean({SessionManagementService.class, HmacSessionTokens.class, CustomerJwtCodec.class})
public class IdentitySessionController {
    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionManagementService sessions;
    private final HmacSessionTokens tokens;
    private final CustomerJwtCodec jwt;

    public IdentitySessionController(
            SessionManagementService sessions, HmacSessionTokens tokens, CustomerJwtCodec jwt) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.jwt = jwt;
    }

    @GetMapping
    public List<SessionView> list(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return sessions.list(digest(authorization), correlation(correlationId));
    }

    @PostMapping("/rotate")
    public RotatedTokenResponse rotate(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        CustomerJwtCodec.RefreshClaims current = refresh(authorization);
        RotatedSession rotated = sessions.rotate(tokens.digest(current.sessionSecret()), correlation(correlationId));
        return new RotatedTokenResponse(
                rotated.sessionId(),
                "Bearer",
                jwt.issueAccess(current.accountId(), rotated.sessionId()),
                jwt.issueRefresh(current.accountId(), rotated.sessionId(), rotated.sessionToken(), rotated.expiresAt()),
                jwt.accessExpiresAt(),
                rotated.expiresAt());
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> logoutCurrent(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        sessions.revokeCurrent(digest(authorization), correlation(correlationId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> logoutOther(
            @PathVariable UUID sessionId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        sessions.revokeOther(digest(authorization), sessionId, correlation(correlationId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> logoutAll(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        sessions.revokeAll(digest(authorization), correlation(correlationId));
        return ResponseEntity.noContent().build();
    }

    private String digest(String authorization) {
        return tokens.digest(refresh(authorization).sessionSecret());
    }

    private CustomerJwtCodec.RefreshClaims refresh(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationRejectedException("Authorization must use a Bearer refresh JWT");
        }
        String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            throw new AuthenticationRejectedException("Bearer refresh JWT is required");
        }
        return jwt.verifyRefresh(rawToken);
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record RotatedTokenResponse(
            UUID sessionId,
            String tokenType,
            String accessToken,
            String refreshToken,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        @Override
        public String toString() {
            return "RotatedTokenResponse[sessionId=" + sessionId + ",tokenType=" + tokenType
                    + ",accessToken=REDACTED,refreshToken=REDACTED,accessExpiresAt=" + accessExpiresAt
                    + ",refreshExpiresAt=" + refreshExpiresAt + "]";
        }
    }
}
