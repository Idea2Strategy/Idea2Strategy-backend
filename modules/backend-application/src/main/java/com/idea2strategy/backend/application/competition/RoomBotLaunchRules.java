package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record RoomBotLaunchRules(
        BigDecimal initialCashAmount,
        UUID feePolicyId,
        UUID buyingPowerBufferPolicyId,
        String precisionRulesVersion) {
    public RoomBotLaunchRules {
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
        Objects.requireNonNull(precisionRulesVersion, "precisionRulesVersion");
    }
}
