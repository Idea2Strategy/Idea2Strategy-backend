package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Why an intent was refused or cut down.
 *
 * <p>The canonical intent already records the decision and its reason code, and the requested
 * against final quantity is what makes a reduction legible rather than merely reported.
 */
public record BotDecisionReasonView(
        UUID intentId,
        UUID partitionId,
        UUID flowId,
        UUID instrumentId,
        String decision,
        String reasonCode,
        BigDecimal requestedQuantity,
        BigDecimal finalQuantity,
        Instant decidedAt) {}
