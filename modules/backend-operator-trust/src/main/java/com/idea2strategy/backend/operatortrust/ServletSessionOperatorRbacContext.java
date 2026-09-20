package com.idea2strategy.backend.operatortrust;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

public final class ServletSessionOperatorRbacContext implements CurrentOperatorRbacContext {
    private final HttpServletRequest request;
    private final OperatorSessionService sessions;
    private final String cookieName;

    public ServletSessionOperatorRbacContext(
            HttpServletRequest request, OperatorSessionService sessions, boolean secureCookie) {
        this.request = request;
        this.sessions = sessions;
        this.cookieName = secureCookie ? "__Host-operator_session" : "operator_session";
    }

    @Override
    public Optional<OperatorRequestContext> current() {
        String raw = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    if (raw != null || cookie.getValue() == null || cookie.getValue().isBlank()) return Optional.empty();
                    raw = cookie.getValue();
                }
            }
        }
        if (raw == null) return Optional.empty();
        try {
            var principal = sessions.authenticate(raw);
            return Optional.of(new OperatorRequestContext(principal.operatorId(), true, true,
                    principal.mfaVerifiedAt(), principal.sessionId()));
        } catch (OperatorAuthenticationRejectedException rejected) {
            return Optional.empty();
        }
    }
}
