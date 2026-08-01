package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.PasswordHash;
import com.idea2strategy.backend.application.identity.PasswordHasher;
import com.idea2strategy.backend.application.identity.PasswordVerifier;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Pbkdf2PasswordCodec implements PasswordHasher, PasswordVerifier {
    static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public PasswordHash hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derived = derive(rawPassword, salt, ITERATIONS);
        String encoded = "v1$" + ITERATIONS + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
        return new PasswordHash(
                encoded,
                "PBKDF2-HMAC-SHA256",
                "{\"iterations\":" + ITERATIONS + ",\"saltBytes\":" + SALT_BYTES
                        + ",\"hashBytes\":" + HASH_BYTES + "}");
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        try {
            String[] parts = encodedPassword.split("\\$", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations != ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(rawPassword, salt, iterations));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing failed", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
