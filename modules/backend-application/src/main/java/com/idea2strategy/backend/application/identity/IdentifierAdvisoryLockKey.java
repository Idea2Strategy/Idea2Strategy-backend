package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public final class IdentifierAdvisoryLockKey {
    private IdentifierAdvisoryLockKey() {}

    public static String of(String kind, String providerCode, IdentifierFingerprint fingerprint) {
        requireText(kind, "kind");
        requireText(providerCode, "providerCode");
        Objects.requireNonNull(fingerprint, "fingerprint");
        return kind + ":" + providerCode + ":" + fingerprint.keyVersion() + ":" + fingerprint.value();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
