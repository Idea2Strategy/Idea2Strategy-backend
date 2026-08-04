package com.idea2strategy.backend.application.strategy;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record ImmutableStrategyReleaseCommand(
        UUID releaseId,
        BigDecimal initialCashAmount,
        int budgetCapBps,
        String brokerRulesVersion,
        String accountingRulesVersion,
        String precisionRulesVersion,
        UUID feePolicyId,
        UUID buyingPowerBufferPolicyId,
        UUID datasetManifestId,
        String executionPolicyVersion,
        String candidateConflictPolicy) {
    public ImmutableStrategyReleaseCommand {
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(brokerRulesVersion, "brokerRulesVersion");
        Objects.requireNonNull(accountingRulesVersion, "accountingRulesVersion");
        Objects.requireNonNull(precisionRulesVersion, "precisionRulesVersion");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        if (executionPolicyVersion == null || executionPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("executionPolicyVersion must not be blank");
        }
        candidateConflictPolicy = StrategyDocumentJson.canonicalize(candidateConflictPolicy);
    }
}
