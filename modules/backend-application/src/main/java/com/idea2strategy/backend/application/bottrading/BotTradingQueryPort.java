package com.idea2strategy.backend.application.bottrading;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the canonical trading tables the trading engine writes.
 *
 * <p>Every method takes the owner account rather than trusting the bot id, because the bot id
 * arrives from the client and ownership is the only thing that makes the answer the caller's to
 * see. An empty {@link Optional} means the bot is not the caller's, which the service turns into
 * the same not-found the rest of the bot API returns.
 */
public interface BotTradingQueryPort {

    Optional<List<BotOrderView>> findOwnedOrders(UUID botId, UUID ownerAccountId, int limit);

    Optional<List<BotFillView>> findOwnedFills(UUID botId, UUID ownerAccountId, int limit);

    Optional<List<BotPositionView>> findOwnedPositions(UUID botId, UUID ownerAccountId);

    Optional<BotBudgetView> findOwnedBudget(UUID botId, UUID ownerAccountId);

    Optional<List<BotDecisionReasonView>> findOwnedDecisionReasons(
            UUID botId, UUID ownerAccountId, int limit);

    Optional<List<BotStopSettlementView>> findOwnedStopSettlement(UUID botId, UUID ownerAccountId);
}
