package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The bot's position in one instrument for one flow, as the canonical flow projection holds it.
 *
 * <p>Long and short are reported separately because canonical keeps them as separate quantities
 * rather than a signed one, and netting them here would hide a flow that holds both.
 */
public record BotPositionView(
        UUID flowId,
        UUID partitionId,
        UUID instrumentId,
        BigDecimal longQuantity,
        BigDecimal shortQuantity,
        BigDecimal costBasisAmount,
        long lastEventSequence) {}
