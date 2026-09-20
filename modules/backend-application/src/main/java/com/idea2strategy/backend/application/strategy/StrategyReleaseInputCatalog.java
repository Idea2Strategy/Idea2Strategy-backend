package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StrategyReleaseInputCatalog(
        List<ExecutionPolicy> executionPolicies,
        List<Dataset> datasets,
        Instant observedAt) {
    public StrategyReleaseInputCatalog {
        executionPolicies = List.copyOf(Objects.requireNonNull(executionPolicies, "executionPolicies"));
        datasets = List.copyOf(Objects.requireNonNull(datasets, "datasets"));
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public record ExecutionPolicy(
            String version,
            String brokerRulesVersion,
            String accountingRulesVersion,
            String precisionRulesVersion,
            UUID feePolicyId,
            int feeRateBps,
            UUID buyingPowerBufferPolicyId,
            int buyingPowerBufferBps,
            LocalDate periodStart,
            LocalDate periodEnd,
            String marketDataSchemaVersion,
            Instant lockedAt) {}

    public record Dataset(
            UUID id,
            UUID instrumentId,
            String feedCode,
            String dataLayer,
            String resolution,
            int revisionNumber,
            LocalDate periodStart,
            LocalDate periodEnd,
            String schemaVersion,
            Instant availableAt) {
        public Dataset(
                UUID id,
                String feedCode,
                String dataLayer,
                String resolution,
                int revisionNumber,
                LocalDate periodStart,
                LocalDate periodEnd,
                String schemaVersion,
                Instant availableAt) {
            this(id, null, feedCode, dataLayer, resolution, revisionNumber, periodStart, periodEnd, schemaVersion, availableAt);
        }
    }
}
