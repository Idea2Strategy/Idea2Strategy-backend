package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record IssuedOidcStepUpNonce(String rawNonce, String digest, short keyVersion) {
    public IssuedOidcStepUpNonce {
        if (Objects.requireNonNull(rawNonce, "rawNonce").isBlank()
                || Objects.requireNonNull(digest, "digest").isBlank()
                || keyVersion < 1) {
            throw new IllegalArgumentException("Issued OIDC nonce must be complete");
        }
    }

    @Override
    public String toString() {
        return "IssuedOidcStepUpNonce[secret=REDACTED,keyVersion=" + keyVersion + "]";
    }
}
