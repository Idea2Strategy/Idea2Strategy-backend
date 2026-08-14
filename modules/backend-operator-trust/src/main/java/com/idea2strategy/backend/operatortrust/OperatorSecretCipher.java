package com.idea2strategy.backend.operatortrust;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class OperatorSecretCipher {
    private final int currentVersion;
    private final Map<Integer, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    public OperatorSecretCipher(int currentVersion, Map<Integer, SecretKey> keys) {
        this.currentVersion = currentVersion;
        this.keys = Map.copyOf(keys);
        if (currentVersion <= 0 || !this.keys.containsKey(currentVersion)) {
            throw new IllegalArgumentException("OPERATOR_TOTP_KEY_CONFIGURATION_INVALID");
        }
    }

    public EncryptedSecret encrypt(UUID operatorId, long credentialVersion, byte[] plaintext) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        return new EncryptedSecret(transform(Cipher.ENCRYPT_MODE, keys.get(currentVersion), nonce,
                aad(operatorId, credentialVersion), plaintext), nonce, currentVersion);
    }

    public byte[] decrypt(UUID operatorId, long credentialVersion, EncryptedSecret encrypted) {
        SecretKey key = keys.get(encrypted.keyVersion());
        if (key == null) throw new IllegalStateException("OPERATOR_TOTP_KEY_UNAVAILABLE");
        return transform(Cipher.DECRYPT_MODE, key, encrypted.nonce(), aad(operatorId, credentialVersion), encrypted.ciphertext());
    }

    private static byte[] transform(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("OPERATOR_TOTP_CRYPTO_FAILED", failure);
        }
    }

    private static byte[] aad(UUID operatorId, long credentialVersion) {
        Objects.requireNonNull(operatorId, "operatorId");
        return ByteBuffer.allocate(24)
                .putLong(operatorId.getMostSignificantBits())
                .putLong(operatorId.getLeastSignificantBits())
                .putLong(credentialVersion)
                .array();
    }

    public record EncryptedSecret(byte[] ciphertext, byte[] nonce, int keyVersion) {
        public EncryptedSecret {
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
            if (nonce.length != 12 || keyVersion <= 0) throw new IllegalArgumentException("OPERATOR_TOTP_CIPHERTEXT_INVALID");
        }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
    }
}
