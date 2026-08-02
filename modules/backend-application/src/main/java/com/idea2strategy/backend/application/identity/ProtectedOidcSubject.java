package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.List;

public record ProtectedOidcSubject(String hmac, short keyVersion,
                                   List<IdentifierFingerprint> comparisonFingerprints) {
    public ProtectedOidcSubject {
        Objects.requireNonNull(hmac, "hmac");
        if (hmac.isBlank() || keyVersion < 1) {
            throw new IllegalArgumentException("Protected OIDC subject must be present");
        }
        comparisonFingerprints = List.copyOf(Objects.requireNonNull(comparisonFingerprints, "comparisonFingerprints"));
        if (comparisonFingerprints.isEmpty()) throw new IllegalArgumentException("Comparison key ring is required");
        if (comparisonFingerprints.stream().noneMatch(fingerprint ->
                fingerprint.keyVersion() == keyVersion && fingerprint.value().equals(hmac))) {
            throw new IllegalArgumentException("Current OIDC fingerprint must be in the comparison key ring");
        }
    }

    public ProtectedOidcSubject(String hmac, short keyVersion) {
        this(hmac, keyVersion, List.of(new IdentifierFingerprint(hmac, keyVersion)));
    }
}
