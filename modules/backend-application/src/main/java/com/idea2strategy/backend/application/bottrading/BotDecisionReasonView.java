package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Why an intent was refused or cut down.
 *
 * <p>The requested against final quantity is what makes a reduction legible rather than merely
 * reported. The time is the batch's, not the intent's: canonical puts no timestamp on an intent,
 * because the decision becomes official when its batch is finalised.
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
        Instant batchFinalizedAt) {}
