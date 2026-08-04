package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.AuthenticatedSession;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

/** Resolves the current application principal from the backend-owned opaque session token. */
public class BearerSessionCurrentPrincipal implements CustomerAccessPrincipal {
    private static final String BEARER_PREFIX = "Bearer ";

    private final HttpServletRequest request;
    private final SessionManagementService sessions;
    private final HmacSessionTokens tokens;
    private AuthenticatedSession resolved;
    private CustomerAccessScope resolvedScope;

    public BearerSessionCurrentPrincipal(
            HttpServletRequest request,
            SessionManagementService sessions,
            HmacSessionTokens tokens) {
        this.request = Objects.requireNonNull(request, "request");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @Override
    public UUID accountId() {
        return accountId(CustomerAccessScope.STANDARD);
    }

    @Override
    public UUID accountId(CustomerAccessScope accessScope) {
        return resolve(accessScope).accountId();
    }

    @Override
    public UUID sessionId() {
        return resolve(CustomerAccessScope.STANDARD).sessionId();
    }

    @Override
    public boolean activeSanction() {
        return resolved != null && resolved.activeSanction();
    }

    private AuthenticatedSession resolve(CustomerAccessScope accessScope) {
        if (resolved != null && resolvedScope == accessScope) return resolved;
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationRejectedException("A bearer session token is required");
        }
        String raw = authorization.substring(BEARER_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            throw new AuthenticationRejectedException("A bearer session token is required");
        }
        resolved = sessions.authenticate(tokens.digest(raw), correlation(), accessScope);
        resolvedScope = accessScope;
        return resolved;
    }

    private UUID correlation() {
        String value = request.getHeader("X-Correlation-Id");
        if (value == null || value.isBlank()) return UUID.randomUUID();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new AuthenticationRejectedException("Correlation identifier is invalid");
        }
    }
}
