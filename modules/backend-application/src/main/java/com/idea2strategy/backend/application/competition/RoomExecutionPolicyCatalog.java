package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RoomExecutionPolicyCatalog(
        List<FeePolicyVersion> feePolicies,
        List<BuyingPowerBufferPolicyVersion> buyingPowerBufferPolicies) {
    public RoomExecutionPolicyCatalog {
        feePolicies = List.copyOf(Objects.requireNonNull(feePolicies, "feePolicies"));
        buyingPowerBufferPolicies = List.copyOf(
                Objects.requireNonNull(buyingPowerBufferPolicies, "buyingPowerBufferPolicies"));
    }

    public record FeePolicyVersion(
            UUID id,
            String policyCode,
            String version,
            int feeRateBps,
            String calculationRulesVersion,
            String rulesHash,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant publishedAt) {
        public FeePolicyVersion {
            Objects.requireNonNull(id, "id");
            policyCode = requireText(policyCode, "policyCode");
            version = requireText(version, "version");
            if (feeRateBps < 0) {
                throw new IllegalArgumentException("feeRateBps must not be negative");
            }
            calculationRulesVersion = requireText(calculationRulesVersion, "calculationRulesVersion");
            rulesHash = requireText(rulesHash, "rulesHash");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }

    public record BuyingPowerBufferPolicyVersion(
            UUID id,
            String policyCode,
            String version,
            int bufferBps,
            String roundingRulesVersion,
            String rulesHash,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant publishedAt) {
        public BuyingPowerBufferPolicyVersion {
            Objects.requireNonNull(id, "id");
            policyCode = requireText(policyCode, "policyCode");
            version = requireText(version, "version");
            if (bufferBps < 0) {
                throw new IllegalArgumentException("bufferBps must not be negative");
            }
            roundingRulesVersion = requireText(roundingRulesVersion, "roundingRulesVersion");
            rulesHash = requireText(rulesHash, "rulesHash");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
