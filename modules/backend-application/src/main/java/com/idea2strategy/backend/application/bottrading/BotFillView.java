package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One individual fill.
 *
 * <p>F09 makes every partial fill a trade in its own right, so this is one row per fill rather than
 * a total per order. The canonical fill carries no revision of its own — a correction is a separate
 * {@code trading.fill_adjustments} row — so none is reported here.
 */
public record BotFillView(
        UUID fillId,
        UUID orderId,
        UUID instrumentId,
        BigDecimal quantity,
        BigDecimal fillPrice,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal settlementCashDelta,
        Instant occurredAt) {}
