package com.idea2strategy.backend.operatortrust;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class InternalOperatorCryptoTest {
    @Test
    void argon2idHashVerifiesThePasswordAndRejectsAnotherPassword() {
        var hasher = new OperatorPasswordHasher(new OperatorPasswordHasher.Parameters(8192, 2, 1, 16, 32, 1));
        char[] password = "correct horse battery staple".toCharArray();
        String encoded = hasher.hash(password);

        assertTrue(hasher.verify("correct horse battery staple".toCharArray(), encoded));
        assertFalse(hasher.verify("wrong password".toCharArray(), encoded));
        assertTrue(encoded.startsWith("$argon2id$v=19$"));
    }

    @Test
    void totpAcceptsOnlyTheAdjacentWindowAndNeverReplaysAStep() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        var totp = new OperatorTotp();
        Instant now = Instant.ofEpochSecond(59);
        String priorWindowCode = totp.code(secret, 0);

        assertTrue(totp.verify(secret, priorWindowCode, now, -1).isPresent());
        assertTrue(totp.verify(secret, priorWindowCode, now, 0).isEmpty());
        assertTrue(totp.verify(secret, totp.code(secret, 3), now, -1).isEmpty());
    }

    @Test
    void aesGcmBindsTotpSeedToOperatorAndCredentialVersion() {
        var key = new SecretKeySpec(new byte[32], "AES");
        var cipher = new OperatorSecretCipher(2, Map.of(2, key));
        UUID operatorId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        byte[] seed = "totp-seed-value".getBytes(StandardCharsets.UTF_8);
        var encrypted = cipher.encrypt(operatorId, 3, seed);

        assertArrayEquals(seed, cipher.decrypt(operatorId, 3, encrypted));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(operatorId, 4, encrypted));
    }

    @Test
    void tokenProtectionSeparatesSessionCsrfAndSourceDomains() {
        byte[] key = new byte[32];
        key[0] = 7;
        var protector = new OperatorTokenProtector(4, Map.of(4, key));
        String token = "opaque-session-token";

        var session = protector.digest(OperatorTokenProtector.Domain.SESSION, token);
        var csrf = protector.deriveCsrf(token, 1);
        var source = protector.digest(OperatorTokenProtector.Domain.SOURCE, "127.0.0.1");

        assertNotEquals(session.hexDigest(), csrf.rawToken());
        assertNotEquals(session.hexDigest(), source.hexDigest());
        assertTrue(protector.matches(OperatorTokenProtector.Domain.SESSION, token, session));
        assertFalse(protector.matches(OperatorTokenProtector.Domain.SESSION, token + "x", session));
    }
}
