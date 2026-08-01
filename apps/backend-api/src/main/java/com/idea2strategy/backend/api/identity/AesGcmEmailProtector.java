package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.EmailLookup;
import com.idea2strategy.backend.application.identity.EmailProtector;
import com.idea2strategy.backend.application.identity.ProtectedEmail;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmEmailProtector implements EmailProtector, EmailLookup {
    private static final int IV_BYTES = 12;
    private final byte[] encryptionKey;
    private final byte[] lookupKey;
    private final short encryptionKeyVersion;
    private final short lookupKeyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmEmailProtector(
            byte[] encryptionKey, byte[] lookupKey, short encryptionKeyVersion, short lookupKeyVersion) {
        if (encryptionKey.length != 32 || lookupKey.length < 32) {
            throw new IllegalArgumentException("Identity encryption and lookup keys must contain at least 256 bits");
        }
        this.encryptionKey = encryptionKey.clone();
        this.lookupKey = lookupKey.clone();
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.lookupKeyVersion = lookupKeyVersion;
    }

    @Override
    public ProtectedEmail protect(String rawEmail) {
        String normalized = normalize(rawEmail);
        return new ProtectedEmail(
                normalized,
                encrypt(normalized),
                hmac(normalized),
                lookupKeyVersion,
                encryptionKeyVersion);
    }

    @Override
    public String lookup(String rawEmail) {
        return hmac(normalize(rawEmail));
    }

    private String encrypt(String normalized) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return "v" + encryptionKeyVersion + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Email protection failed", exception);
        }
    }

    private String hmac(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(lookupKey, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Email lookup protection failed", exception);
        }
    }

    private static String normalize(String rawEmail) {
        return Normalizer.normalize(rawEmail.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
