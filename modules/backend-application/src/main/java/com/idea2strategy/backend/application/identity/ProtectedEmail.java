package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.List;

public record ProtectedEmail(
        String normalized,
        String ciphertext,
        String lookupHmac,
        short lookupKeyVersion,
        short encryptionKeyVersion,
        List<IdentifierFingerprint> comparisonFingerprints) {
    public ProtectedEmail {
        requireText(normalized, "normalized");
        requireText(ciphertext, "ciphertext");
        requireText(lookupHmac, "lookupHmac");
        if (lookupKeyVersion < 1 || encryptionKeyVersion < 1) {
            throw new IllegalArgumentException("Key versions must be positive");
        }
        comparisonFingerprints = List.copyOf(Objects.requireNonNull(comparisonFingerprints, "comparisonFingerprints"));
        if (comparisonFingerprints.isEmpty()) throw new IllegalArgumentException("Comparison key ring is required");
        if (comparisonFingerprints.stream().noneMatch(fingerprint ->
                fingerprint.keyVersion() == lookupKeyVersion && fingerprint.value().equals(lookupHmac))) {
            throw new IllegalArgumentException("Current email lookup fingerprint must be in the comparison key ring");
        }
    }

    public ProtectedEmail(String normalized, String ciphertext, String lookupHmac,
                          short lookupKeyVersion, short encryptionKeyVersion) {
        this(normalized, ciphertext, lookupHmac, lookupKeyVersion, encryptionKeyVersion,
                List.of(new IdentifierFingerprint(lookupHmac, lookupKeyVersion)));
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
