package com.idea2strategy.backend.operatortrust;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Servlet adapter that recognizes only one explicit bearer credential. */
public final class ServletBearerOperatorRbacContext implements CurrentOperatorRbacContext {
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final HttpServletRequest request;
    private final OperatorBearerAuthenticationService authentication;
    private final OperatorAuthenticationEventSink events;

    public ServletBearerOperatorRbacContext(
            HttpServletRequest request,
            OperatorBearerAuthenticationService authentication,
            OperatorAuthenticationEventSink events) {
        this.request = Objects.requireNonNull(request, "request");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public Optional<OperatorRequestContext> current() {
        UUID correlationId = correlationId();
        Enumeration<String> values = request.getHeaders(AUTHORIZATION);
        if (values == null || !values.hasMoreElements()) return rejected(correlationId, "OPERATOR_BEARER_MISSING");
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null
                || value.length() < BEARER_PREFIX.length()
                || !value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return rejected(correlationId, "OPERATOR_BEARER_INVALID");
        }
        String token = value.substring(BEARER_PREFIX.length());
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            return rejected(correlationId, "OPERATOR_BEARER_INVALID");
        }
        return authentication.authenticate(token, correlationId);
    }

    private Optional<OperatorRequestContext> rejected(UUID correlationId, String reasonCode) {
        events.rejected(correlationId, reasonCode);
        return Optional.empty();
    }

    private UUID correlationId() {
        String value = request.getHeader("X-Correlation-Id");
        try {
            return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return UUID.randomUUID();
        }
    }
}
