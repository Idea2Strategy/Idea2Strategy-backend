package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityCryptoTest {
    private static final byte[] ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HMAC_KEY = "identity-lookup-key-material-32b"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void protectsEmailWithEncryptionAndStableKeyedLookup() {
        var protector = new AesGcmEmailProtector(ENCRYPTION_KEY, HMAC_KEY, (short) 1, (short) 1);

        var first = protector.protect(" Person@Example.COM ");
        var second = protector.protect("person@example.com");

        assertThat(first.normalized()).isEqualTo("person@example.com");
        assertThat(first.ciphertext()).doesNotContain("person@example.com");
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.lookupHmac()).isEqualTo(second.lookupHmac()).isEqualTo(protector.lookup("person@example.com"));
        assertThat(protector.reveal(first.ciphertext())).isEqualTo("person@example.com");
    }

    @Test
    void protectsEmailAgainstCurrentAndPreviousLookupKeys() {
        byte[] previous = "previous-email-lookup-key-32byte".getBytes(StandardCharsets.UTF_8);
        var protector = new AesGcmEmailProtector(
                ENCRYPTION_KEY, HMAC_KEY, (short) 2, (short) 2, Map.of((short) 1, previous));

        assertThat(protector.protect("person@example.com").comparisonFingerprints())
                .extracting(com.idea2strategy.backend.application.identity.IdentifierFingerprint::keyVersion)
                .containsExactlyInAnyOrder((short) 2, (short) 1);
    }

    @Test
    void hashesPasswordsWithPbkdf2AndUniqueSalt() {
        var codec = new Pbkdf2PasswordCodec();

        var first = codec.hash("a sufficiently long passphrase");
        var second = codec.hash("a sufficiently long passphrase");

        assertThat(first.encodedHash()).isNotEqualTo(second.encodedHash());
        assertThat(first.scheme()).isEqualTo("PBKDF2-HMAC-SHA256");
        assertThat(first.parametersJson()).contains("600000");
        assertThat(codec.matches("a sufficiently long passphrase", first.encodedHash())).isTrue();
        assertThat(codec.matches("a different long passphrase", first.encodedHash())).isFalse();
    }

    @Test
    void opaqueTokensExposeRawValueOnceAndPersistOnlyHmacDigest() {
        var verification = new HmacVerificationTokens(HMAC_KEY);
        var session = new HmacRefreshTokenSecrets(HMAC_KEY);

        var verificationToken = verification.issue();
        var sessionToken = session.issue();

        assertThat(verificationToken.digest()).isEqualTo(verification.digest(verificationToken.rawToken()));
        assertThat(verificationToken.digest()).doesNotContain(verificationToken.rawToken());
        assertThat(sessionToken.digest()).doesNotContain(sessionToken.rawToken());
        assertThat(verificationToken.rawToken()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(sessionToken.rawToken()).hasSizeGreaterThanOrEqualTo(40);
    }
}
