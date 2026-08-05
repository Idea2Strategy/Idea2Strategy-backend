package com.idea2strategy.backend.batch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EncryptedNotificationRecipientResolver implements NotificationRecipientResolver {
    private static final int IV_BYTES = 12;
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private final short keyVersion;

    public EncryptedNotificationRecipientResolver(JdbcTemplate jdbc, byte[] key, short keyVersion) {
        if (key == null || key.length != 32 || keyVersion < 1) {
            throw new IllegalArgumentException("Email encryption key must contain 256 bits and a positive version");
        }
        this.jdbc = jdbc;
        this.key = key.clone();
        this.keyVersion = keyVersion;
    }

    @Override
    public String requireEmail(UUID accountId) {
        String protectedEmail = jdbc.queryForObject("""
                select email_ciphertext from identity.account_emails where account_id = ?
                """, String.class, accountId);
        if (protectedEmail == null || !protectedEmail.startsWith("v" + keyVersion + ":")) {
            throw new IllegalStateException("Recipient email key version is unavailable");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(protectedEmail.substring(protectedEmail.indexOf(':') + 1));
            if (payload.length <= IV_BYTES) throw new IllegalStateException("Recipient email payload is invalid");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(payload, 0, IV_BYTES)));
            return new String(cipher.doFinal(Arrays.copyOfRange(payload, IV_BYTES, payload.length)),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Recipient email could not be opened", exception);
        }
    }

    @Override
    public String toString() {
        return "EncryptedNotificationRecipientResolver[database-backed]";
    }
}
