package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.RotatedSession;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import com.idea2strategy.backend.application.identity.SessionView;
import java.util.List;
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
@ConditionalOnBean({SessionManagementService.class, HmacSessionTokens.class})
public class IdentitySessionController {
    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionManagementService sessions;
    private final HmacSessionTokens tokens;

    public IdentitySessionController(SessionManagementService sessions, HmacSessionTokens tokens) {
        this.sessions = sessions;
        this.tokens = tokens;
    }

    @GetMapping
    public List<SessionView> list(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return sessions.list(digest(authorization), correlation(correlationId));
    }

    @PostMapping("/rotate")
    public RotatedSession rotate(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return sessions.rotate(digest(authorization), correlation(correlationId));
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
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new IllegalArgumentException("Authorization must use a Bearer session token");
        }
        String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            throw new IllegalArgumentException("Bearer session token is required");
        }
        return tokens.digest(rawToken);
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }
}
