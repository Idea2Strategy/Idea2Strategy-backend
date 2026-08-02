package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One liquidation the forced stop generated, as {@code trading.system_close_actions} records it.
 *
 * <p>Each action names the intent it produced, which is what lets the settlement result be followed
 * into the orders and fills that carried it out.
 */
public record BotStopSettlementView(
        UUID actionId,
        UUID partitionId,
        UUID flowId,
        UUID instrumentId,
        String symbol,
        String currentSymbol,
        String reasonType,
        BigDecimal requestedQuantity,
        UUID generatedIntentId,
        Instant createdAt) {}
