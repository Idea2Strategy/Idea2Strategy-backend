package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.RotatedRefreshToken;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.RefreshTokenService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnBean({RefreshTokenService.class, HmacRefreshTokenSecrets.class, CustomerJwtCodec.class})
public class IdentityTokenController {
    private static final String BEARER_PREFIX = "Bearer ";

    private final RefreshTokenService refreshTokens;
    private final HmacRefreshTokenSecrets refreshTokenSecrets;
    private final CustomerJwtCodec jwt;
    private final RefreshTokenCookie refreshCookie;

    public IdentityTokenController(
            RefreshTokenService refreshTokens,
            HmacRefreshTokenSecrets refreshTokenSecrets,
            CustomerJwtCodec jwt,
            RefreshTokenCookie refreshCookie) {
        this.refreshTokens = refreshTokens;
        this.refreshTokenSecrets = refreshTokenSecrets;
        this.jwt = jwt;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping({"/refresh", "/sessions/rotate"})
    public ResponseEntity<RotatedTokenResponse> rotate(
            @CookieValue(value = RefreshTokenCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        CustomerJwtCodec.RefreshClaims current = refresh(refreshCookieValue, authorization);
        RotatedRefreshToken rotated = refreshTokens.rotate(
                current.familyId(), refreshTokenSecrets.digest(current.tokenSecret()), correlation(correlationId));
        String replacementRefresh = jwt.issueRefresh(
                rotated.accountId(),
                rotated.familyId(),
                rotated.loginIdentityId(),
                rotated.authEpoch(),
                rotated.credentialVersion(),
                rotated.tokenSecret(),
                rotated.expiresAt());
        var body = new RotatedTokenResponse(
                rotated.accountId(),
                "Bearer",
                jwt.issueAccess(
                        rotated.accountId(),
                        rotated.loginIdentityId(),
                        rotated.authEpoch(),
                        rotated.credentialVersion()),
                jwt.accessExpiresAt(),
                rotated.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie.issue(replacementRefresh, rotated.expiresAt()).toString(),
                        refreshCookie.clearLegacyPath().toString())
                .body(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logoutCurrent(
            @CookieValue(value = RefreshTokenCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        CustomerJwtCodec.RefreshClaims current = refresh(refreshCookieValue, authorization);
        refreshTokens.revokeCurrent(
                current.familyId(), refreshTokenSecrets.digest(current.tokenSecret()), correlation(correlationId));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(
            @CookieValue(value = RefreshTokenCookie.NAME, required = false) String refreshCookieValue,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        CustomerJwtCodec.RefreshClaims current = refresh(refreshCookieValue, authorization);
        refreshTokens.revokeAll(
                current.familyId(), refreshTokenSecrets.digest(current.tokenSecret()), correlation(correlationId));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    private CustomerJwtCodec.RefreshClaims refresh(String refreshCookieValue, String authorization) {
        if (refreshCookieValue != null && !refreshCookieValue.isBlank()) {
            return jwt.verifyRefresh(refreshCookieValue.trim());
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationRejectedException("A refresh token cookie is required");
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
            String tokenType,
            String accessToken,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        @Override
        public String toString() {
            return "RotatedTokenResponse[accountId=" + accountId + ",tokenType=" + tokenType
                    + ",accessToken=REDACTED,accessExpiresAt=" + accessExpiresAt
                    + ",refreshExpiresAt=" + refreshExpiresAt + "]";
        }
    }
}
