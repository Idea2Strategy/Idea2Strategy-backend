package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.SessionToken;
import com.idea2strategy.backend.application.identity.SessionTokenIssuer;

public final class HmacSessionTokens implements SessionTokenIssuer {
    private final byte[] key;

    public HmacSessionTokens(byte[] key) {
        this.key = key.clone();
    }

    @Override
    public SessionToken issue() {
        String raw = OpaqueTokenSupport.issueRaw();
        return new SessionToken(raw, OpaqueTokenSupport.digest(key, raw));
    }

    public String digest(String rawToken) {
        return OpaqueTokenSupport.digest(key, rawToken);
    }
}
