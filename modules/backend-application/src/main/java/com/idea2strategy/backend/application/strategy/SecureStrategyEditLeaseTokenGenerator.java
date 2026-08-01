package com.idea2strategy.backend.application.strategy;

import java.security.SecureRandom;
import java.util.Base64;

public final class SecureStrategyEditLeaseTokenGenerator implements StrategyEditLeaseTokenGenerator {
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;

    public SecureStrategyEditLeaseTokenGenerator() {
        this(new SecureRandom());
    }

    SecureStrategyEditLeaseTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public String nextToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
