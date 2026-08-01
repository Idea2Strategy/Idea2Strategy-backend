package com.idea2strategy.backend.application.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacOidcSubjectProtector implements OidcSubjectProtector {
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;
    private final short keyVersion;

    public HmacOidcSubjectProtector(byte[] key, short keyVersion) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32 || keyVersion < 1) {
            throw new IllegalArgumentException("OIDC subject HMAC requires a 256-bit key and positive version");
        }
        this.key = key.clone();
        this.keyVersion = keyVersion;
    }

    @Override
    public ProtectedOidcSubject protect(VerifiedOidcPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        String canonical = component(principal.providerCode())
                + component(principal.issuer())
                + component(principal.subject());
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            String digest = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return new ProtectedOidcSubject(digest, keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OIDC subject protection is unavailable", exception);
        }
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }
}
