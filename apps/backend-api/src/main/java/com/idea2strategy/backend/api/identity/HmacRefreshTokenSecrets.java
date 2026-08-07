package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.RefreshTokenSecret;
import com.idea2strategy.backend.application.identity.RefreshTokenSecretIssuer;

public final class HmacRefreshTokenSecrets implements RefreshTokenSecretIssuer {
    private final byte[] key;

    public HmacRefreshTokenSecrets(byte[] key) {
        this.key = key.clone();
    }

    @Override
    public RefreshTokenSecret issue() {
        String raw = OpaqueTokenSupport.issueRaw();
        return new RefreshTokenSecret(raw, OpaqueTokenSupport.digest(key, raw));
    }

    public String digest(String rawToken) {
        return OpaqueTokenSupport.digest(key, rawToken);
    }
}
