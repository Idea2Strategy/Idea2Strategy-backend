package com.idea2strategy.backend.api.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class OpaqueTokenSupport {
    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokenSupport() {}

    static String issueRaw() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String digest(byte[] key, String raw) {
        if (key.length < 32) {
            throw new IllegalArgumentException("Token HMAC key must contain at least 256 bits");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Token digest failed", exception);
        }
    }
}
