package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.VerificationToken;
import com.idea2strategy.backend.application.identity.VerificationTokenDigest;
import com.idea2strategy.backend.application.identity.VerificationTokenIssuer;

public final class HmacVerificationTokens implements VerificationTokenIssuer, VerificationTokenDigest {
    private final byte[] key;

    public HmacVerificationTokens(byte[] key) {
        this.key = key.clone();
    }

    @Override
    public VerificationToken issue() {
        String raw = OpaqueTokenSupport.issueRaw();
        return new VerificationToken(raw, digest(raw));
    }

    @Override
    public String digest(String rawToken) {
        return OpaqueTokenSupport.digest(key, rawToken);
    }
}
