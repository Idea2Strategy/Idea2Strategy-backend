package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record VirtualLiquidationContext(
        UUID roomId,
        UUID participationId,
        UUID botId,
        UUID evaluationSegmentId,
        Instant startsAt,
        Instant endsAt,
        long startEventSequence,
        BigDecimal initialCapitalAmount,
        UUID feePolicyId,
        String feeRulesHash,
        int slippageRateBps,
        String roomRulesHash) {
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");

    public VirtualLiquidationContext {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(initialCapitalAmount, "initialCapitalAmount");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        feeRulesHash = requireHash(feeRulesHash, "feeRulesHash");
        roomRulesHash = requireHash(roomRulesHash, "roomRulesHash");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("evaluation segment must be non-empty");
        }
        if (startEventSequence < 0) {
            throw new IllegalArgumentException("startEventSequence must not be negative");
        }
        if (initialCapitalAmount.signum() <= 0) {
            throw new IllegalArgumentException("initialCapitalAmount must be positive");
        }
        if (slippageRateBps < 0) {
            throw new IllegalArgumentException("slippageRateBps must not be negative");
        }
    }

    static String requireHash(String value, String field) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be sha256 followed by 64 lowercase hex characters");
        }
        return value;
    }
}
