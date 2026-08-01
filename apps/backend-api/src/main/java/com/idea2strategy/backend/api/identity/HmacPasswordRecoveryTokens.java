package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.PasswordResetToken;
import com.idea2strategy.backend.application.identity.PasswordResetTokenDigest;
import com.idea2strategy.backend.application.identity.PasswordResetTokenIssuer;

public final class HmacPasswordRecoveryTokens implements PasswordResetTokenIssuer, PasswordResetTokenDigest {
    private final byte[] key;

    public HmacPasswordRecoveryTokens(byte[] key) {
        this.key = key.clone();
    }

    @Override
    public PasswordResetToken issue() {
        String raw = OpaqueTokenSupport.issueRaw();
        return new PasswordResetToken(raw, digest(raw));
    }

    @Override
    public String digest(String rawToken) {
        return OpaqueTokenSupport.digest(key, "account-recovery:" + rawToken);
    }
}
