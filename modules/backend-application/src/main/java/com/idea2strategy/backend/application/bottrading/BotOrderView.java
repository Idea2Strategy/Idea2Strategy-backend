package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One order of the bot.
 *
 * <p>The status and the filled quantity come from {@code trading.order_state_projections} rather
 * than from the order row, because canonical keeps the order immutable and moves its state there.
 */
public record BotOrderView(
        UUID orderId,
        UUID partitionId,
        UUID instrumentId,
        String side,
        String orderType,
        String timeInForce,
        BigDecimal requestedQuantity,
        BigDecimal filledQuantity,
        BigDecimal remainingQuantity,
        String status,
        Instant acceptedAt) {}
