package com.idea2strategy.backend.application.bottrading;

import com.idea2strategy.backend.application.botoperations.BotOperationsNotFoundException;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of the trading and ledger surface.
 *
 * <p>There is deliberately no write method here. {@code policy.user.no-direct-orders} says a user
 * cannot submit an order or an order intention outside their locked strategy, so the API that shows
 * what the bot did offers no way to add to it.
 */
public final class BotTradingQueryService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final BotTradingQueryPort queryPort;
    private final CurrentPrincipal principal;

    public BotTradingQueryService(BotTradingQueryPort queryPort, CurrentPrincipal principal) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public List<BotOrderView> listOrders(UUID botId, Integer limit) {
        return required(botId, queryPort.findOwnedOrders(botId, owner(), pageSize(limit)));
    }

    public List<BotFillView> listFills(UUID botId, Integer limit) {
        return required(botId, queryPort.findOwnedFills(botId, owner(), pageSize(limit)));
    }

    public List<BotPositionView> listPositions(UUID botId) {
        return required(botId, queryPort.findOwnedPositions(botId, owner()));
    }

    public BotBudgetView getBudget(UUID botId) {
        return required(botId, queryPort.findOwnedBudget(botId, owner()));
    }

    public List<BotDecisionReasonView> listDecisionReasons(UUID botId, Integer limit) {
        return required(botId, queryPort.findOwnedDecisionReasons(botId, owner(), pageSize(limit)));
    }

    public List<BotStopSettlementView> listStopSettlement(UUID botId) {
        return required(botId, queryPort.findOwnedStopSettlement(botId, owner()));
    }

    private UUID owner() {
        return principal.accountId();
    }

    private static <T> T required(UUID botId, Optional<T> found) {
        return found.orElseThrow(() -> new BotOperationsNotFoundException(botId));
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return limit;
    }
}
