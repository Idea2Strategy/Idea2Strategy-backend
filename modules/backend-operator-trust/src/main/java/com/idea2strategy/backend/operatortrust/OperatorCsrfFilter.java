package com.idea2strategy.backend.operatortrust;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

public final class OperatorCsrfFilter extends OncePerRequestFilter {
    private final OperatorSessionService sessions;
    private final String cookieName;

    public OperatorCsrfFilter(OperatorSessionService sessions, boolean secureCookie) {
        this.sessions = sessions;
        this.cookieName = secureCookie ? "__Host-operator_session" : "operator_session";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) return true;
        String path = request.getRequestURI();
        return path.equals("/api/v1/operator-auth/sessions")
                || !(path.startsWith("/api/v1/operations/")
                || path.equals("/api/v1/operator-auth/logout")
                || path.equals("/api/v1/operator-auth/reauthenticate")
                || path.startsWith("/mcp/v1/tools/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        String origin = request.getHeader("Origin");
        String expectedOrigin = request.getScheme() + "://" + request.getServerName()
                + ((request.getServerPort() == 80 || request.getServerPort() == 443) ? "" : ":" + request.getServerPort());
        if ((fetchSite != null && !(fetchSite.equals("same-origin") || fetchSite.equals("none")))
                || (origin != null && !origin.equals(expectedOrigin))) {
            reject(response); return;
        }
        String raw = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if (cookieName.equals(cookie.getName())) raw = cookie.getValue();
        try {
            if (raw == null || !sessions.csrfMatches(raw, request.getHeader("X-Operator-CSRF"))) {
                reject(response); return;
            }
        } catch (RuntimeException rejected) {
            reject(response); return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getOutputStream().write("{\"code\":\"OPERATOR_AUTHENTICATION_REJECTED\"}"
                .getBytes(StandardCharsets.UTF_8));
    }
}
