package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.IssuedOidcStepUpNonce;
import com.idea2strategy.backend.application.identity.OidcStepUpNonceSupport;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacOidcStepUpNonces implements OidcStepUpNonceSupport {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] key;
    private final short keyVersion;
    private final SecureRandom random;

    public HmacOidcStepUpNonces(byte[] key, short keyVersion) {
        this(key, keyVersion, new SecureRandom());
    }

    HmacOidcStepUpNonces(byte[] key, short keyVersion, SecureRandom random) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32 || keyVersion < 1) {
            throw new IllegalArgumentException("OIDC nonce HMAC requires a 256-bit key and positive version");
        }
        this.key = key.clone();
        this.keyVersion = keyVersion;
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public IssuedOidcStepUpNonce issue() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new IssuedOidcStepUpNonce(raw, digest(raw), keyVersion);
    }

    @Override
    public String digest(String rawNonce) {
        Objects.requireNonNull(rawNonce, "rawNonce");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(rawNonce.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OIDC nonce protection is unavailable", exception);
        }
    }
}
