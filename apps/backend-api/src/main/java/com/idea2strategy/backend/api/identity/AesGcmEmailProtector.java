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
import java.util.LinkedHashMap;
import java.util.Map;
import com.idea2strategy.backend.application.identity.IdentifierFingerprint;
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
    private final Map<Short, byte[]> comparisonKeys;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmEmailProtector(
            byte[] encryptionKey, byte[] lookupKey, short encryptionKeyVersion, short lookupKeyVersion) {
        this(encryptionKey, lookupKey, encryptionKeyVersion, lookupKeyVersion, Map.of());
    }

    public AesGcmEmailProtector(byte[] encryptionKey, byte[] lookupKey,
                                short encryptionKeyVersion, short lookupKeyVersion,
                                Map<Short, byte[]> previousLookupKeys) {
        if (encryptionKey.length != 32 || lookupKey.length < 32) {
            throw new IllegalArgumentException("Identity encryption and lookup keys must contain at least 256 bits");
        }
        this.encryptionKey = encryptionKey.clone();
        this.lookupKey = lookupKey.clone();
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.lookupKeyVersion = lookupKeyVersion;
        var keys = new LinkedHashMap<Short, byte[]>();
        keys.put(lookupKeyVersion, lookupKey.clone());
        previousLookupKeys.forEach((version, key) -> {
            if (version == null || version < 1 || key == null || key.length < 32 || keys.containsKey(version)) {
                throw new IllegalArgumentException("Every comparison lookup key needs a unique positive version and 256 bits");
            }
            keys.put(version, key.clone());
        });
        this.comparisonKeys = Map.copyOf(keys);
    }

    @Override
    public ProtectedEmail protect(String rawEmail) {
        String normalized = normalize(rawEmail);
        return new ProtectedEmail(
                normalized,
                encrypt(normalized),
                hmac(normalized),
                lookupKeyVersion,
                encryptionKeyVersion,
                comparisonKeys.entrySet().stream()
                        .map(entry -> new IdentifierFingerprint(hmac(normalized, entry.getValue()), entry.getKey()))
                        .toList());
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
        return hmac(normalized, lookupKey);
    }

    private static String hmac(String normalized, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
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
