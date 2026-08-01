package com.idea2strategy.backend.domain.strategy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record StrategyValidationRun(
        UUID id,
        UUID strategyId,
        UUID requestedByAccountId,
        UUID delegatedAuthorizationId,
        long requestedEditSequence,
        String semanticHash,
        UUID elementCatalogVersionId,
        StrategyValidationStatus status,
        List<StrategyValidationFinding> findings,
        Instant requestedAt,
        Instant completedAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public StrategyValidationRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(strategyId, "strategyId");
        Objects.requireNonNull(requestedByAccountId, "requestedByAccountId");
        Objects.requireNonNull(elementCatalogVersionId, "elementCatalogVersionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
        findings = List.copyOf(findings);
        if (requestedEditSequence < 0) {
            throw new IllegalArgumentException("requestedEditSequence must not be negative");
        }
        if (semanticHash == null || !SHA_256.matcher(semanticHash).matches()) {
            throw new IllegalArgumentException("semanticHash must be a lowercase SHA-256 digest");
        }
        if (status == StrategyValidationStatus.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("running validation must not be completed");
        }
        if (status != StrategyValidationStatus.RUNNING && completedAt == null) {
            throw new IllegalArgumentException("completed validation must have completedAt");
        }
        boolean blocking = findings.stream()
                .anyMatch(finding -> finding.severity() == StrategyValidationFinding.Severity.BLOCKING_ERROR);
        if (status == StrategyValidationStatus.VALID && blocking) {
            throw new IllegalArgumentException("valid validation must not contain blocking errors");
        }
        if (status == StrategyValidationStatus.INVALID && !blocking) {
            throw new IllegalArgumentException("invalid validation must contain a blocking error");
        }
    }

    public int issueCount() {
        return findings.size();
    }
}
