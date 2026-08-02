package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.Objects;

public record ClosureReadiness(
        ClosureDomain domain,
        ClosureReadinessStatus status,
        String reasonCode,
        String evidence,
        Instant observedAt) {
    public ClosureReadiness {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
