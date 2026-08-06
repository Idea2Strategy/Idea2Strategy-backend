package com.idea2strategy.backend.api.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerificationException;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerificationRequest;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerifier;
import com.idea2strategy.backend.application.identity.VerifiedOidcIdToken;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TrustedOidcIdTokenVerifier implements OidcIdTokenVerifier {
    private static final int MAX_TOKEN_LENGTH = 65_536;
    private static final int MAX_PROVIDERS = 32;
    private static final int MAX_KEYS_PER_PROVIDER = 16;
    private static final int MAX_NEGATIVE_KIDS_PER_PROVIDER = 32;
    private static final Duration KEY_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration UNKNOWN_KID_THROTTLE = Duration.ofSeconds(30);

    private final ObjectMapper json;
    private final JwksSource jwksSource;
    private final Clock clock;
    private final Duration maximumAuthenticationAge;
    private final Map<String, TrustedOidcProviderConfiguration> providers;
    private final Map<String, ProviderKeyCache> keyCaches;

    public TrustedOidcIdTokenVerifier(
            ObjectMapper json,
            JwksSource jwksSource,
            Clock clock,
            Duration maximumAuthenticationAge,
            Map<String, TrustedOidcProviderConfiguration> providers) {
        this.json = Objects.requireNonNull(json, "json");
        this.jwksSource = Objects.requireNonNull(jwksSource, "jwksSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAuthenticationAge = Objects.requireNonNull(maximumAuthenticationAge, "maximumAuthenticationAge");
        this.providers = Map.copyOf(Objects.requireNonNull(providers, "providers"));
        if (maximumAuthenticationAge.isZero()
                || maximumAuthenticationAge.isNegative()
                || providers.isEmpty()
                || providers.size() > MAX_PROVIDERS) {
            throw new IllegalArgumentException("OIDC verifier configuration is invalid");
        }
        providers.forEach((code, provider) -> {
            if (!code.equals(provider.providerCode())) {
                throw new IllegalArgumentException("OIDC provider map key must match provider code");
            }
        });
        var caches = new LinkedHashMap<String, ProviderKeyCache>();
        providers.keySet().forEach(code -> caches.put(code, new ProviderKeyCache()));
        this.keyCaches = Map.copyOf(caches);
    }

    @Override
    public VerifiedOidcIdToken verify(OidcIdTokenVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        TrustedOidcProviderConfiguration provider = providers.get(request.providerCode());
        if (provider == null) {
            throw rejected();
        }

        try {
            if (request.idToken().length() > MAX_TOKEN_LENGTH) {
                throw rejected();
            }
            String[] parts = request.idToken().split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw rejected();
            }

            JsonNode header = decodeJson(parts[0]);
            String algorithm = requiredText(header, "alg");
            String keyId = requiredText(header, "kid");
            if (!"RS256".equals(algorithm) || header.has("crit")) {
                throw rejected();
            }

            Instant verifiedAt = clock.instant();
            RSAPublicKey publicKey = selectPublicKey(provider, keyId, verifiedAt);
            verifySignature(parts, publicKey);

            JsonNode claims = decodeJson(parts[1]);
            String issuer = requiredText(claims, "iss");
            String subject = requiredText(claims, "sub");
            AudienceClaim audienceClaim = requiredAudience(claims.get("aud"));
            Set<String> audience = audienceClaim.values();
            String nonce = requiredText(claims, "nonce");
            Instant expiresAt = requiredEpochSecond(claims, "exp");
            Instant issuedAt = optionalEpochSecond(claims, "iat");
            Instant authenticatedAt = optionalEpochSecond(claims, "auth_time");
            if (authenticatedAt == null) {
                authenticatedAt = issuedAt;
            }

            if (!provider.issuer().equals(issuer)
                    || !validAudience(claims, audienceClaim, provider.audiences())
                    || authenticatedAt == null
                    || (request.expectedNonce() != null && !request.expectedNonce().equals(nonce))
                    || !expiresAt.isAfter(verifiedAt)
                    || (issuedAt != null && issuedAt.isAfter(verifiedAt))
                    || authenticatedAt.isAfter(verifiedAt)
                    || authenticatedAt.isBefore(verifiedAt.minus(maximumAuthenticationAge))) {
                throw rejected();
            }

            JsonNode emailClaim = claims.get("email");
            String email = emailClaim != null && emailClaim.isTextual() && !emailClaim.textValue().isBlank()
                    ? emailClaim.textValue()
                    : null;
            if ("GOOGLE".equals(provider.providerCode())
                    && (email == null || !claims.path("email_verified").asBoolean(false))) {
                throw rejected();
            }
            return new VerifiedOidcIdToken(
                    provider.providerCode(),
                    issuer,
                    subject,
                    audience,
                    nonce,
                    email,
                    authenticatedAt,
                    expiresAt,
                    verifiedAt,
                    keyId);
        } catch (OidcIdTokenVerificationException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OidcIdTokenVerificationException("OIDC ID token verification failed", exception);
        } catch (Exception exception) {
            throw new OidcIdTokenVerificationException("OIDC ID token verification failed", exception);
        }
    }

    private JsonNode decodeJson(String encoded) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        JsonNode node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        if (node == null || !node.isObject()) {
            throw rejected();
        }
        return node;
    }

    private RSAPublicKey selectPublicKey(
            TrustedOidcProviderConfiguration provider, String keyId, Instant now) throws Exception {
        ProviderKeyCache cache = keyCaches.get(provider.providerCode());
        RSAPublicKey cached = cache.keys.get(keyId);
        if (cached != null && now.isBefore(cache.expiresAt)) {
            return cached;
        }
        Instant negativeUntil = cache.negativeKids.get(keyId);
        if (negativeUntil != null && now.isBefore(negativeUntil)) {
            throw rejected();
        }
        if (now.isBefore(cache.refreshBlockedUntil)) {
            throw rejected();
        }
        synchronized (cache) {
            cached = cache.keys.get(keyId);
            if (cached != null && now.isBefore(cache.expiresAt)) {
                return cached;
            }
            negativeUntil = cache.negativeKids.get(keyId);
            if (negativeUntil != null && now.isBefore(negativeUntil)) {
                throw rejected();
            }
            if (now.isBefore(cache.refreshBlockedUntil)) {
                throw rejected();
            }
            boolean freshCache = now.isBefore(cache.expiresAt);
            if (freshCache
                    && cache.lastUnknownKidRefreshAt != null
                    && now.isBefore(cache.lastUnknownKidRefreshAt.plus(UNKNOWN_KID_THROTTLE))) {
                rememberNegative(cache, keyId, now);
                throw rejected();
            }
            if (freshCache) {
                cache.lastUnknownKidRefreshAt = now;
            }
            try {
                cache.keys = loadPublicKeys(provider);
                cache.refreshBlockedUntil = Instant.EPOCH;
            } catch (Exception exception) {
                cache.refreshBlockedUntil = now.plus(UNKNOWN_KID_THROTTLE);
                throw exception;
            }
            cache.expiresAt = now.plus(KEY_CACHE_TTL);
            cache.negativeKids.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
            cached = cache.keys.get(keyId);
            if (cached == null) {
                rememberNegative(cache, keyId, now);
                throw rejected();
            }
            return cached;
        }
    }

    private Map<String, RSAPublicKey> loadPublicKeys(TrustedOidcProviderConfiguration provider) throws Exception {
        JsonNode jwks = json.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(jwksSource.load(provider.jwksUri()));
        JsonNode keys = jwks == null ? null : jwks.get("keys");
        if (keys == null || !keys.isArray()) {
            throw rejected();
        }
        var loaded = new LinkedHashMap<String, RSAPublicKey>();
        for (JsonNode key : keys) {
            if (!"RSA".equals(text(key, "kty"))
                    || (key.has("use") && !"sig".equals(text(key, "use")))
                    || (key.has("alg") && !"RS256".equals(text(key, "alg")))) {
                continue;
            }
            String loadedKeyId = requiredText(key, "kid");
            BigInteger modulus = unsignedInteger(requiredText(key, "n"));
            BigInteger exponent = unsignedInteger(requiredText(key, "e"));
            if (modulus.bitLength() < 2048 || loaded.size() >= MAX_KEYS_PER_PROVIDER) {
                throw rejected();
            }
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            if (loaded.putIfAbsent(loadedKeyId, publicKey) != null) {
                throw rejected();
            }
        }
        if (loaded.isEmpty()) {
            throw rejected();
        }
        return Map.copyOf(loaded);
    }

    private static void rememberNegative(ProviderKeyCache cache, String keyId, Instant now) {
        if (cache.negativeKids.size() < MAX_NEGATIVE_KIDS_PER_PROVIDER || cache.negativeKids.containsKey(keyId)) {
            cache.negativeKids.put(keyId, now.plus(UNKNOWN_KID_THROTTLE));
        }
    }

    private void verifySignature(String[] parts, RSAPublicKey publicKey) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
            throw rejected();
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw rejected();
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static AudienceClaim requiredAudience(JsonNode claim) {
        Set<String> audiences = new HashSet<>();
        boolean multiple = false;
        if (claim != null && claim.isTextual() && !claim.textValue().isBlank()) {
            audiences.add(claim.textValue());
        } else if (claim != null && claim.isArray()) {
            multiple = claim.size() > 1;
            for (JsonNode value : claim) {
                if (!value.isTextual() || value.textValue().isBlank()) {
                    throw rejected();
                }
                audiences.add(value.textValue());
            }
        }
        if (audiences.isEmpty()) {
            throw rejected();
        }
        return new AudienceClaim(Set.copyOf(audiences), multiple);
    }

    private static boolean validAudience(
            JsonNode claims, AudienceClaim audience, Set<String> allowedAudiences) {
        if (!audience.multiple()) {
            return audience.values().size() == 1 && allowedAudiences.contains(audience.values().iterator().next());
        }
        String authorizedParty = text(claims, "azp");
        return authorizedParty != null
                && !authorizedParty.isBlank()
                && audience.values().contains(authorizedParty)
                && allowedAudiences.contains(authorizedParty);
    }

    private static Instant requiredEpochSecond(JsonNode claims, String field) {
        JsonNode value = claims.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw rejected();
        }
        try {
            return Instant.ofEpochSecond(value.longValue());
        } catch (RuntimeException exception) {
            throw rejected();
        }
    }

    private static Instant optionalEpochSecond(JsonNode claims, String field) {
        if (!claims.has(field)) {
            return null;
        }
        return requiredEpochSecond(claims, field);
    }

    private static BigInteger unsignedInteger(String encoded) {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        if (bytes.length == 0) {
            throw rejected();
        }
        return new BigInteger(1, bytes);
    }

    private static OidcIdTokenVerificationException rejected() {
        return new OidcIdTokenVerificationException("OIDC ID token verification failed");
    }

    private record AudienceClaim(Set<String> values, boolean multiple) {}

    private static final class ProviderKeyCache {
        private volatile Map<String, RSAPublicKey> keys = Map.of();
        private volatile Instant expiresAt = Instant.EPOCH;
        private volatile Instant refreshBlockedUntil = Instant.EPOCH;
        private volatile Instant lastUnknownKidRefreshAt;
        private final ConcurrentHashMap<String, Instant> negativeKids = new ConcurrentHashMap<>();
    }
}
