package com.idea2strategy.backend.api.bottrading;

import com.idea2strategy.backend.application.bottrading.BotBudgetView;
import com.idea2strategy.backend.application.bottrading.BotDecisionReasonView;
import com.idea2strategy.backend.application.bottrading.BotFillView;
import com.idea2strategy.backend.application.bottrading.BotOrderView;
import com.idea2strategy.backend.application.bottrading.BotPositionView;
import com.idea2strategy.backend.application.bottrading.BotStopSettlementView;
import com.idea2strategy.backend.application.bottrading.BotTradingQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the bot did, in trading and ledger terms.
 *
 * <p>Every method here is a GET. {@code policy.user.no-direct-orders} is approved and says a user
 * cannot submit an order or an order intention outside their locked strategy, so this controller
 * deliberately offers no way to place, amend or cancel one — the bot's own execution is the only
 * thing that writes to these tables.
 */
@RestController
@RequestMapping("/api/v1/bots")
@ConditionalOnBean(BotTradingQueryService.class)
public class BotTradingController {
    private final BotTradingQueryService queryService;

    public BotTradingController(BotTradingQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{botId}/orders")
    public List<BotOrderView> orders(
            @PathVariable UUID botId, @RequestParam(required = false) Integer limit) {
        return queryService.listOrders(botId, limit);
    }

    @GetMapping("/{botId}/fills")
    public List<BotFillView> fills(
            @PathVariable UUID botId, @RequestParam(required = false) Integer limit) {
        return queryService.listFills(botId, limit);
    }

    @GetMapping("/{botId}/positions")
    public List<BotPositionView> positions(@PathVariable UUID botId) {
        return queryService.listPositions(botId);
    }

    @GetMapping("/{botId}/budget")
    public BotBudgetView budget(@PathVariable UUID botId) {
        return queryService.getBudget(botId);
    }

    @GetMapping("/{botId}/decision-reasons")
    public List<BotDecisionReasonView> decisionReasons(
            @PathVariable UUID botId, @RequestParam(required = false) Integer limit) {
        return queryService.listDecisionReasons(botId, limit);
    }

    @GetMapping("/{botId}/stop-settlement")
    public List<BotStopSettlementView> stopSettlement(@PathVariable UUID botId) {
        return queryService.listStopSettlement(botId);
    }
}
