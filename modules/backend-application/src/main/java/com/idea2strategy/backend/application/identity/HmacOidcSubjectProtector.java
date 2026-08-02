package com.idea2strategy.backend.application.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacOidcSubjectProtector implements OidcSubjectProtector {
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;
    private final short keyVersion;
    private final Map<Short, byte[]> comparisonKeys;

    public HmacOidcSubjectProtector(byte[] key, short keyVersion) {
        this(key, keyVersion, Map.of());
    }

    public HmacOidcSubjectProtector(byte[] key, short keyVersion, Map<Short, byte[]> previousKeys) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32 || keyVersion < 1) {
            throw new IllegalArgumentException("OIDC subject HMAC requires a 256-bit key and positive version");
        }
        this.key = key.clone();
        this.keyVersion = keyVersion;
        var keys = new LinkedHashMap<Short, byte[]>();
        keys.put(keyVersion, key.clone());
        previousKeys.forEach((version, candidate) -> {
            if (version == null || version < 1 || candidate == null || candidate.length < 32
                    || keys.containsKey(version)) {
                throw new IllegalArgumentException("Every OIDC comparison key needs a unique positive version and 256 bits");
            }
            keys.put(version, candidate.clone());
        });
        this.comparisonKeys = Map.copyOf(keys);
    }

    @Override
    public ProtectedOidcSubject protect(VerifiedOidcPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        String canonical = component(principal.providerCode())
                + component(principal.issuer())
                + component(principal.subject());
        String digest = digest(canonical, key);
        return new ProtectedOidcSubject(digest, keyVersion,
                comparisonKeys.entrySet().stream()
                        .map(entry -> new IdentifierFingerprint(digest(canonical, entry.getValue()), entry.getKey()))
                        .toList());
    }

    private static String digest(String canonical, byte[] key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OIDC subject protection is unavailable", exception);
        }
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }
}
