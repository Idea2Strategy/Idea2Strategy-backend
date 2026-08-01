package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record ProtectedEmail(
        String normalized,
        String ciphertext,
        String lookupHmac,
        short lookupKeyVersion,
        short encryptionKeyVersion) {
    public ProtectedEmail {
        requireText(normalized, "normalized");
        requireText(ciphertext, "ciphertext");
        requireText(lookupHmac, "lookupHmac");
        if (lookupKeyVersion < 1 || encryptionKeyVersion < 1) {
            throw new IllegalArgumentException("Key versions must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    public String toString() {
        return "ProtectedEmail[REDACTED]";
    }
}
