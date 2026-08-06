package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

/** Resolves the current customer principal from a locally verified short-lived access JWT. */
public class BearerSessionCurrentPrincipal implements CustomerAccessPrincipal {
    private static final String BEARER_PREFIX = "Bearer ";

    private final HttpServletRequest request;
    private final CustomerJwtCodec jwt;
    private CustomerJwtCodec.AccessClaims resolved;

    public BearerSessionCurrentPrincipal(
            HttpServletRequest request,
            CustomerJwtCodec jwt) {
        this.request = Objects.requireNonNull(request, "request");
        this.jwt = Objects.requireNonNull(jwt, "jwt");
    }

    @Override
    public UUID accountId() {
        return accountId(CustomerAccessScope.STANDARD);
    }

    @Override
    public UUID accountId(CustomerAccessScope accessScope) {
        Objects.requireNonNull(accessScope, "accessScope");
        return resolve().accountId();
    }

    @Override
    public UUID sessionId() {
        return resolve().sessionId();
    }

    @Override
    public boolean activeSanction() {
        return false;
    }

    private CustomerJwtCodec.AccessClaims resolve() {
        if (resolved != null) return resolved;
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationRejectedException("A bearer access JWT is required");
        }
        String raw = authorization.substring(BEARER_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            throw new AuthenticationRejectedException("A bearer access JWT is required");
        }
        resolved = jwt.verifyAccess(raw);
        return resolved;
    }
}
