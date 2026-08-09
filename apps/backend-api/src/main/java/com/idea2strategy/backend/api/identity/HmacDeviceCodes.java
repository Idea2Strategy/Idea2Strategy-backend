package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.DeviceCodeMaterial;
import com.idea2strategy.backend.application.identity.DeviceCodeMaterialPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two codes a device authorization runs on.
 *
 * <p>The user code is typed by a person, so it is short and drops characters that are misread aloud
 * or on screen — no 0/O, 1/I, or vowels that could spell something. That deliberately makes it
 * weak, which is why it can only ever request approval. The device code is a full 256-bit secret
 * and is the only thing that collects a token.
 *
 * <p>Both are stored as digests, so a database reader cannot approve or collect on someone's
 * behalf, and the short lifetime bounds what a stolen digest could be replayed against.
 */
public final class HmacDeviceCodes implements DeviceCodeMaterialPort {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789".toCharArray();
    private static final int USER_CODE_HALF = 4;

    private final byte[] key;
    private final short keyVersion;

    public HmacDeviceCodes(byte[] key, short keyVersion) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) {
            throw new IllegalArgumentException("Device code HMAC key must contain at least 256 bits");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("Device code key version must be positive");
        }
        this.key = key.clone();
        this.keyVersion = keyVersion;
    }

    @Override
    public DeviceCodeMaterial issue() {
        byte[] deviceBytes = new byte[32];
        RANDOM.nextBytes(deviceBytes);
        String deviceCode = Base64.getUrlEncoder().withoutPadding().encodeToString(deviceBytes);
        String userCode = userCode();
        return new DeviceCodeMaterial(
                deviceCode, digest(deviceCode), userCode, digest(userCode), keyVersion);
    }

    @Override
    public String digestDeviceCode(String deviceCode) {
        return digest(deviceCode);
    }

    /** Case and dashes are presentation; a person retyping the code should not fail on either. */
    @Override
    public String digestUserCode(String userCode) {
        return digest(userCode.replace("-", "").trim().toUpperCase(java.util.Locale.ROOT));
    }

    private String userCode() {
        StringBuilder code = new StringBuilder(USER_CODE_HALF * 2 + 1);
        for (int index = 0; index < USER_CODE_HALF * 2; index++) {
            if (index == USER_CODE_HALF) {
                code.append('-');
            }
            code.append(USER_CODE_ALPHABET[RANDOM.nextInt(USER_CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Device code digest failed", exception);
        }
    }
}
