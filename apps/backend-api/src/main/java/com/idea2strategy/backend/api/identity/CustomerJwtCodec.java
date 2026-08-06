package com.idea2strategy.backend.api.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Issues and verifies customer access and refresh JWTs with the shared runtime HMAC key. */
public final class CustomerJwtCodec {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final byte[] HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final byte[] key;
    private final Clock clock;
    private final String issuer;
    private final String accessAudience;
    private final String refreshAudience;
    private final Duration accessLifetime;
    private final ObjectMapper json = new ObjectMapper();

    public CustomerJwtCodec(
            byte[] key,
            Clock clock,
            String issuer,
            String accessAudience,
            String refreshAudience,
            Duration accessLifetime) {
        this.key = Objects.requireNonNull(key, "key").clone();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.issuer = requireText(issuer, "issuer");
        this.accessAudience = requireText(accessAudience, "accessAudience");
        this.refreshAudience = requireText(refreshAudience, "refreshAudience");
        this.accessLifetime = Objects.requireNonNull(accessLifetime, "accessLifetime");
        if (key.length < 32 || accessLifetime.isZero() || accessLifetime.isNegative()) {
            throw new IllegalArgumentException("Customer JWT key and lifetime are invalid");
        }
    }

    public String issueAccess(UUID accountId, UUID sessionId) {
        Instant now = clock.instant();
        return issue(accountId, sessionId, "access", accessAudience, now, now.plus(accessLifetime), Map.of());
    }

    public String issueRefresh(UUID accountId, UUID sessionId, String sessionSecret, Instant expiresAt) {
        Instant now = clock.instant();
        if (!Objects.requireNonNull(expiresAt, "expiresAt").isAfter(now)) {
            throw new IllegalArgumentException("Refresh token expiry must be in the future");
        }
        return issue(accountId, sessionId, "refresh", refreshAudience, now, expiresAt,
                Map.of("st", requireText(sessionSecret, "sessionSecret")));
    }

    public Instant accessExpiresAt() {
        return clock.instant().plus(accessLifetime);
    }

    public AccessClaims verifyAccess(String token) {
        Map<String, Object> claims = verify(token, "access", accessAudience);
        return new AccessClaims(uuid(claims, "sub"), uuid(claims, "sid"), instant(claims, "exp"));
    }

    public RefreshClaims verifyRefresh(String token) {
        Map<String, Object> claims = verify(token, "refresh", refreshAudience);
        return new RefreshClaims(
                uuid(claims, "sub"),
                uuid(claims, "sid"),
                text(claims, "st"),
                instant(claims, "exp"));
    }

    private String issue(
            UUID accountId,
            UUID sessionId,
            String type,
            String audience,
            Instant issuedAt,
            Instant expiresAt,
            Map<String, String> additional) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            var claims = new LinkedHashMap<String, Object>();
            claims.put("iss", issuer);
            claims.put("aud", audience);
            claims.put("sub", accountId.toString());
            claims.put("sid", sessionId.toString());
            claims.put("typ", type);
            claims.put("iat", issuedAt.getEpochSecond());
            claims.put("exp", expiresAt.getEpochSecond());
            claims.putAll(additional);
            String header = URL_ENCODER.encodeToString(HEADER);
            String payload = URL_ENCODER.encodeToString(json.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return signingInput + "." + URL_ENCODER.encodeToString(sign(signingInput));
        } catch (Exception exception) {
            throw new IllegalStateException("Customer JWT issuance failed", exception);
        }
    }

    private Map<String, Object> verify(String token, String expectedType, String expectedAudience) {
        try {
            String[] parts = Objects.requireNonNull(token, "token").split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw rejected();
            }
            Map<String, Object> header = json.readValue(URL_DECODER.decode(parts[0]), MAP);
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ")) || header.containsKey("crit")) {
                throw rejected();
            }
            byte[] expectedSignature = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expectedSignature, URL_DECODER.decode(parts[2]))) {
                throw rejected();
            }
            Map<String, Object> claims = json.readValue(URL_DECODER.decode(parts[1]), MAP);
            Instant now = clock.instant();
            Instant issuedAt = instant(claims, "iat");
            Instant expiresAt = instant(claims, "exp");
            if (!issuer.equals(text(claims, "iss"))
                    || !expectedAudience.equals(text(claims, "aud"))
                    || !expectedType.equals(text(claims, "typ"))
                    || issuedAt.isAfter(now.plusSeconds(30))
                    || !expiresAt.isAfter(now)) {
                throw rejected();
            }
            uuid(claims, "sub");
            uuid(claims, "sid");
            return claims;
        } catch (AuthenticationRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected();
        }
    }

    private byte[] sign(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
    }

    private static String text(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw rejected();
        return text;
    }

    private static UUID uuid(Map<String, Object> claims, String name) {
        try {
            return UUID.fromString(text(claims, name));
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private static Instant instant(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number number)) throw rejected();
        try {
            return Instant.ofEpochSecond(number.longValue());
        } catch (RuntimeException exception) {
            throw rejected();
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static AuthenticationRejectedException rejected() {
        return new AuthenticationRejectedException("Customer JWT is invalid");
    }

    public record AccessClaims(UUID accountId, UUID sessionId, Instant expiresAt) {}

    public record RefreshClaims(UUID accountId, UUID sessionId, String sessionSecret, Instant expiresAt) {
        @Override
        public String toString() {
            return "RefreshClaims[accountId=" + accountId + ",sessionId=" + sessionId
                    + ",sessionSecret=REDACTED,expiresAt=" + expiresAt + "]";
        }
    }
}
