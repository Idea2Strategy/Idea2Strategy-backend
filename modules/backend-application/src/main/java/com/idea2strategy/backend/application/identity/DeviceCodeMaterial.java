package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record DeviceCodeMaterial(
        String deviceCode,
        String deviceCodeDigest,
        String userCode,
        String userCodeDigest,
        short digestKeyVersion) {
    public DeviceCodeMaterial {
        Objects.requireNonNull(deviceCode, "deviceCode");
        Objects.requireNonNull(deviceCodeDigest, "deviceCodeDigest");
        Objects.requireNonNull(userCode, "userCode");
        Objects.requireNonNull(userCodeDigest, "userCodeDigest");
        if (digestKeyVersion < 1) {
            throw new IllegalArgumentException("digest key version must be positive");
        }
    }
}
