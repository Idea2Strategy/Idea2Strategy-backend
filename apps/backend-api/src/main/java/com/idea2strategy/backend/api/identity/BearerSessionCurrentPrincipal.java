package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.AuthenticatedCustomer;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import com.idea2strategy.backend.application.identity.CustomerAccessValidationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

/** Resolves the current customer principal from a locally verified short-lived access JWT. */
public class BearerSessionCurrentPrincipal implements CustomerAccessPrincipal {
    private static final String BEARER_PREFIX = "Bearer ";

    private final HttpServletRequest request;
    private final CustomerJwtCodec jwt;
    private final CustomerAccessValidationService validation;
    private final EnumMap<CustomerAccessScope, AuthenticatedCustomer> authenticated =
            new EnumMap<>(CustomerAccessScope.class);
    private CustomerJwtCodec.AccessClaims claims;

    public BearerSessionCurrentPrincipal(
            HttpServletRequest request,
            CustomerJwtCodec jwt,
            CustomerAccessValidationService validation) {
        this.request = Objects.requireNonNull(request, "request");
        this.jwt = Objects.requireNonNull(jwt, "jwt");
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    @Override
    public UUID accountId() {
        return accountId(CustomerAccessScope.STANDARD);
    }

    @Override
    public UUID accountId(CustomerAccessScope accessScope) {
        Objects.requireNonNull(accessScope, "accessScope");
        return authenticate(accessScope).accountId();
    }

    @Override
    public boolean activeSanction() {
        return authenticate(CustomerAccessScope.APPEAL).activeSanction();
    }

    private AuthenticatedCustomer authenticate(CustomerAccessScope accessScope) {
        Objects.requireNonNull(accessScope, "accessScope");
        return authenticated.computeIfAbsent(accessScope, ignored -> {
            var resolved = resolveClaims();
            return validation.authenticate(
                    resolved.accountId(),
                    resolved.loginIdentityId(),
                    resolved.authEpoch(),
                    resolved.credentialVersion(),
                    accessScope);
        });
    }

    private CustomerJwtCodec.AccessClaims resolveClaims() {
        if (claims != null) return claims;
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationRejectedException("A bearer access JWT is required");
        }
        String raw = authorization.substring(BEARER_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            throw new AuthenticationRejectedException("A bearer access JWT is required");
        }
        claims = jwt.verifyAccess(raw);
        return claims;
    }

}
