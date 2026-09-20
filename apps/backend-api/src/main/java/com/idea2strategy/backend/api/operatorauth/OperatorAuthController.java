package com.idea2strategy.backend.api.operatorauth;

import com.idea2strategy.backend.operatortrust.OperatorAuthenticationRejectedException;
import com.idea2strategy.backend.operatortrust.OperatorSessionService;
import com.idea2strategy.backend.operatortrust.OperatorTrustProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator-auth")
@ConditionalOnProperty(prefix = "idea2strategy.operator-auth", name = "enabled", havingValue = "true")
public class OperatorAuthController {
    static final String PRODUCTION_COOKIE = "__Host-operator_session";
    static final String LOCAL_COOKIE = "operator_session";
    private final OperatorSessionService sessions;
    private final boolean secureCookie;

    public OperatorAuthController(OperatorSessionService sessions, OperatorTrustProperties properties) {
        this.sessions = sessions;
        this.secureCookie = properties.isSecureCookie();
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionResponse> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        var issued = sessions.login(request.loginName(), request.password().toCharArray(), request.totpCode(),
                servletRequest.getRemoteAddr());
        var body = new SessionResponse(issued.sessionId(), issued.operatorId(), issued.rawCsrfToken(), issued.idleExpiresAt(),
                issued.absoluteExpiresAt(), issued.mfaVerifiedAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie(issued.rawSessionToken(), issued.absoluteExpiresAt()).toString())
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(
            @CookieValue(name = PRODUCTION_COOKIE, required = false) String production,
            @CookieValue(name = LOCAL_COOKIE, required = false) String local) {
        String raw = cookieValue(production, local);
        var view = sessions.inspect(raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionResponse(
                null, view.operatorId(), view.rawCsrfToken(), view.idleExpiresAt(), view.absoluteExpiresAt(),
                view.mfaVerifiedAt()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = PRODUCTION_COOKIE, required = false) String production,
            @CookieValue(name = LOCAL_COOKIE, required = false) String local,
            @RequestHeader(name = "X-Operator-CSRF", required = false) String csrf) {
        String raw = cookieValue(production, local);
        if (!sessions.csrfMatches(raw, csrf)) throw new OperatorAuthenticationRejectedException();
        sessions.logout(raw);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/reauthenticate")
    public ResponseEntity<SessionResponse> reauthenticate(
            @CookieValue(name = PRODUCTION_COOKIE, required = false) String production,
            @CookieValue(name = LOCAL_COOKIE, required = false) String local,
            @RequestHeader(name = "X-Operator-CSRF", required = false) String csrf,
            @RequestBody ReauthenticateRequest request) {
        String raw = cookieValue(production, local);
        if (!sessions.csrfMatches(raw, csrf)) throw new OperatorAuthenticationRejectedException();
        var refreshed = sessions.reauthenticate(raw, request.password().toCharArray(), request.totpCode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionResponse(
                null, refreshed.operatorId(), refreshed.rawCsrfToken(), refreshed.idleExpiresAt(),
                refreshed.absoluteExpiresAt(), refreshed.mfaVerifiedAt()));
    }

    private String cookieValue(String production, String local) {
        String value = secureCookie ? production : local;
        if (value == null || value.isBlank()) throw new OperatorAuthenticationRejectedException();
        return value;
    }

    private ResponseCookie cookie(String value, Instant absoluteExpiry) {
        Duration maxAge = Duration.between(Instant.now(), absoluteExpiry);
        if (maxAge.isNegative()) maxAge = Duration.ZERO;
        return ResponseCookie.from(secureCookie ? PRODUCTION_COOKIE : LOCAL_COOKIE, value)
                .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/").maxAge(maxAge).build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(secureCookie ? PRODUCTION_COOKIE : LOCAL_COOKIE, "")
                .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/").maxAge(Duration.ZERO).build();
    }

    public record LoginRequest(String loginName, String password, String totpCode) {
        public LoginRequest {
            if (loginName == null || password == null || totpCode == null) {
                throw new OperatorAuthenticationRejectedException();
            }
        }
    }
    public record ReauthenticateRequest(String password, String totpCode) {
        public ReauthenticateRequest {
            if (password == null || totpCode == null) throw new OperatorAuthenticationRejectedException();
        }
    }
    public record SessionResponse(UUID sessionId, UUID operatorId, String csrfToken, Instant idleExpiresAt,
                                  Instant absoluteExpiresAt, Instant mfaVerifiedAt) {}
}
