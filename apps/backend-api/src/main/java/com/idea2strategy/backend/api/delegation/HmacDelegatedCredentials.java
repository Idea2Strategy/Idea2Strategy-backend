package com.idea2strategy.backend.api.delegation;

import com.idea2strategy.backend.application.delegation.DelegatedCredentialMaterial;
import com.idea2strategy.backend.application.delegation.DelegatedCredentialMaterialPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues the secret a delegated tool holds.
 *
 * <p>Only the digest is stored, so a database reader cannot replay a delegation, and the raw value
 * is returned exactly once at grant. This mirrors how customer refresh tokens are handled; the key
 * version travels with the digest so a future key rotation can tell old rows from new ones instead
 * of invalidating every delegation at once.
 */
public final class HmacDelegatedCredentials implements DelegatedCredentialMaterialPort {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final short keyVersion;

    public HmacDelegatedCredentials(byte[] key, short keyVersion) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) {
            throw new IllegalArgumentException("Delegated credential HMAC key must contain at least 256 bits");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("Delegated credential key version must be positive");
        }
        this.key = key.clone();
        this.keyVersion = keyVersion;
    }

    @Override
    public DelegatedCredentialMaterial issue() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        return new DelegatedCredentialMaterial(raw, digest(raw), keyVersion);
    }

    private String digest(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Delegated credential digest failed", exception);
        }
    }
}
