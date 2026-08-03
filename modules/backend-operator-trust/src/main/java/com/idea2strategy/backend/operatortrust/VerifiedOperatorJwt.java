package com.idea2strategy.backend.operatortrust;

import java.time.Instant;
import java.util.Objects;

public record VerifiedOperatorJwt(
        String issuer,
        String subject,
        Instant authenticatedAt,
        boolean currentMfa) {
    public VerifiedOperatorJwt {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
    }
}
