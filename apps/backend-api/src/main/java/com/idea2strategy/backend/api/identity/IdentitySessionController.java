package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.RotatedSession;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import com.idea2strategy.backend.application.identity.SessionView;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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
    private final RefreshSessionCookie refreshCookie;

    public IdentitySessionController(
            SessionManagementService sessions,
            HmacSessionTokens tokens,
            CustomerJwtCodec jwt,
            RefreshSessionCookie refreshCookie) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.jwt = jwt;
        this.refreshCookie = refreshCookie;
    }

    @GetMapping
    public List<SessionView> list(
            @CookieValue(value = RefreshSessionCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return sessions.list(digest(refreshCookieValue, authorization), correlation(correlationId));
    }

    @PostMapping("/rotate")
    public ResponseEntity<RotatedTokenResponse> rotate(
            @CookieValue(value = RefreshSessionCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        CustomerJwtCodec.RefreshClaims current = refresh(refreshCookieValue, authorization);
        RotatedSession rotated = sessions.rotate(tokens.digest(current.sessionSecret()), correlation(correlationId));
        String replacementRefresh = jwt.issueRefresh(
                current.accountId(), rotated.sessionId(), rotated.sessionToken(), rotated.expiresAt());
        var body = new RotatedTokenResponse(
                current.accountId(),
                rotated.sessionId(),
                "Bearer",
                jwt.issueAccess(current.accountId(), rotated.sessionId()),
                jwt.accessExpiresAt(),
                rotated.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie.issue(replacementRefresh, rotated.expiresAt()).toString())
                .body(body);
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> logoutCurrent(
            @CookieValue(value = RefreshSessionCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        sessions.revokeCurrent(digest(refreshCookieValue, authorization), correlation(correlationId));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> logoutOther(
            @PathVariable UUID sessionId,
            @CookieValue(value = RefreshSessionCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        sessions.revokeOther(digest(refreshCookieValue, authorization), sessionId, correlation(correlationId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> logoutAll(
            @CookieValue(value = RefreshSessionCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        sessions.revokeAll(digest(refreshCookieValue, authorization), correlation(correlationId));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    private String digest(String refreshCookieValue, String authorization) {
        return tokens.digest(refresh(refreshCookieValue, authorization).sessionSecret());
    }

    private CustomerJwtCodec.RefreshClaims refresh(String refreshCookieValue, String authorization) {
        if (refreshCookieValue != null && !refreshCookieValue.isBlank()) {
            return jwt.verifyRefresh(refreshCookieValue.trim());
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationRejectedException("A refresh session cookie is required");
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
            UUID accountId,
            UUID sessionId,
            String tokenType,
            String accessToken,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        @Override
        public String toString() {
            return "RotatedTokenResponse[accountId=" + accountId + ",sessionId=" + sessionId + ",tokenType=" + tokenType
                    + ",accessToken=REDACTED,accessExpiresAt=" + accessExpiresAt
                    + ",refreshExpiresAt=" + refreshExpiresAt + "]";
        }
    }
}
