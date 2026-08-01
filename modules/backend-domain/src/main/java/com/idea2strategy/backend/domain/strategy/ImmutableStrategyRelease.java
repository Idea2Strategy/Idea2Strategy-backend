package com.idea2strategy.backend.domain.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ImmutableStrategyRelease(
        UUID botId,
        UUID ownerAccountId,
        String name,
        String description,
        String semanticSnapshot,
        String presentationSnapshot,
        String semanticHash,
        String presentationHash,
        String snapshotHash,
        LaunchConfiguration launchConfiguration,
        Partition partition,
        Instant releasedAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ImmutableStrategyRelease {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        requireText(name, "name");
        if (name.length() > 120) {
            throw new IllegalArgumentException("name must not exceed 120 characters");
        }
        requireText(semanticSnapshot, "semanticSnapshot");
        requireText(presentationSnapshot, "presentationSnapshot");
        requireHash(semanticHash, "semanticHash");
        requireHash(presentationHash, "presentationHash");
        requireHash(snapshotHash, "snapshotHash");
        Objects.requireNonNull(launchConfiguration, "launchConfiguration");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(releasedAt, "releasedAt");
    }

    public record LaunchConfiguration(
            BigDecimal initialCashAmount,
            String brokerRulesVersion,
            String accountingRulesVersion,
            String precisionRulesVersion,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            String candidateConflictPolicy,
            String configurationHash) {
        public LaunchConfiguration {
            Objects.requireNonNull(initialCashAmount, "initialCashAmount");
            if (initialCashAmount.signum() <= 0) {
                throw new IllegalArgumentException("initialCashAmount must be positive");
            }
            requireText(brokerRulesVersion, "brokerRulesVersion");
            requireText(accountingRulesVersion, "accountingRulesVersion");
            requireText(precisionRulesVersion, "precisionRulesVersion");
            Objects.requireNonNull(feePolicyId, "feePolicyId");
            Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
            requireText(candidateConflictPolicy, "candidateConflictPolicy");
            requireHash(configurationHash, "configurationHash");
        }
    }

    public record Partition(
            UUID id,
            String name,
            String description,
            int budgetCapBps,
            String configurationHash,
            List<Flow> flows) {
        public Partition {
            Objects.requireNonNull(id, "id");
            requireText(name, "name");
            if (budgetCapBps <= 0 || budgetCapBps > 10_000) {
                throw new IllegalArgumentException("budgetCapBps must be in 1..10000");
            }
            requireHash(configurationHash, "configurationHash");
            flows = List.copyOf(Objects.requireNonNull(flows, "flows"));
            if (flows.isEmpty()) {
                throw new IllegalArgumentException("release must contain at least one flow");
            }
        }
    }

    public record Flow(
            UUID id,
            String name,
            UUID elementCatalogVersionId,
            UUID compiledFlowPlanId,
            String semanticDocument,
            String layoutDocument,
            String semanticHash,
            String layoutHash,
            String configurationHash,
            List<UUID> instrumentIds,
            List<FeatureRequirement> featureRequirements,
            int positionOrder) {
        public Flow {
            Objects.requireNonNull(id, "id");
            requireText(name, "name");
            Objects.requireNonNull(elementCatalogVersionId, "elementCatalogVersionId");
            Objects.requireNonNull(compiledFlowPlanId, "compiledFlowPlanId");
            requireText(semanticDocument, "semanticDocument");
            requireText(layoutDocument, "layoutDocument");
            requireHash(semanticHash, "semanticHash");
            requireHash(layoutHash, "layoutHash");
            requireHash(configurationHash, "configurationHash");
            instrumentIds = List.copyOf(Objects.requireNonNull(instrumentIds, "instrumentIds"));
            featureRequirements = List.copyOf(Objects.requireNonNull(featureRequirements, "featureRequirements"));
            if (instrumentIds.isEmpty() || positionOrder < 0) {
                throw new IllegalArgumentException("flow instruments and positionOrder are invalid");
            }
        }
    }

    public record FeatureRequirement(UUID instrumentId, UUID featureDefinitionId) {
        public FeatureRequirement {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(featureDefinitionId, "featureDefinitionId");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireHash(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
    }
}
