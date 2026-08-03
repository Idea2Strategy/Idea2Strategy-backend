package com.idea2strategy.backend.application.bottrading;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The bot's position in one instrument for one flow, as the canonical flow projection holds it.
 *
 * <p>Long and short are reported separately because canonical keeps them as separate quantities
 * rather than a signed one, and netting them here would hide a flow that holds both.
 *
 * <p>The valuation triple follows the v1 mark rule that F93 fixed for the live performance
 * producers: an instrument is worth the latest canonical fill reference price observed anywhere in
 * the engine before the read instant. {@code currentPrice} is that mark, {@code unrealisedPnl} is
 * the net exposure at the mark against the projection's remaining cost basis, and
 * {@code returnPct} is that gain over the basis, in percent. An instrument no fill has ever touched
 * has no mark, and a basis of zero has no return, so each of the three is null exactly when its
 * input does not exist — the screen keeps its dash rather than being handed an invented number.
 */
public record BotPositionView(
        UUID flowId,
        UUID partitionId,
        UUID instrumentId,
        String currentSymbol,
        BigDecimal longQuantity,
        BigDecimal shortQuantity,
        BigDecimal costBasisAmount,
        BigDecimal currentPrice,
        BigDecimal unrealisedPnl,
        BigDecimal returnPct,
        long lastEventSequence) {}
