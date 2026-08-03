package com.idea2strategy.backend.operatortrust;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

final class OperatorTrustTestFixtures {
    static OperatorTrustConfiguration configuration() {
        return new OperatorTrustConfiguration(
                "https://operator.example", URI.create("https://operator.example/jwks"),
                "operator-api", Duration.ofMinutes(5), Duration.ofMinutes(10),
                Duration.ofSeconds(30), Set.of("urn:mfa"), Set.of("mfa", "otp"),
                Map.of(2, key(2), 1, key(1)), 2);
    }

    static byte[] key(int marker) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) marker);
        return key;
    }

    private OperatorTrustTestFixtures() {}
}
