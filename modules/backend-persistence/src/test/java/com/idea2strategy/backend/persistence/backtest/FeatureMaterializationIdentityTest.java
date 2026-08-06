package com.idea2strategy.backend.persistence.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureMaterializationIdentityTest {
    @Test
    void derivesTheCanonicalOfficialRsiFeedIdWithoutNormalizingStoredInputs() {
        assertThat(FeatureMaterializationPinResolver.deterministicUuid(
                        "feature-output-feed",
                        "sha256:1a7c3e5b9d2f4068a1c3e5b7d9f20416283a5c7e9b1d3f50627496a8c0e2b4d6",
                        "rsi:1.0.0",
                        "1m",
                        FeatureMaterializationPinResolver.OUTPUT_SCHEMA))
                .isEqualTo(UUID.fromString("063f8f27-5c6a-5348-b2bb-abc3c634149c"));
    }
}
