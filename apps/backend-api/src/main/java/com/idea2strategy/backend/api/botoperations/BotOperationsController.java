package com.idea2strategy.backend.api.botoperations;

import com.idea2strategy.backend.application.botoperations.BotJudgmentLogPage;
import com.idea2strategy.backend.application.botoperations.BotOperationsQueryService;
import com.idea2strategy.backend.application.botoperations.BotOperationsView;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bots")
@ConditionalOnBean(BotOperationsQueryService.class)
public class BotOperationsController {
    private final BotOperationsQueryService queryService;

    public BotOperationsController(BotOperationsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/operations")
    public List<BotOperationsView> listOperations() {
        return queryService.listOwned();
    }

    @GetMapping("/{botId}/judgments")
    public BotJudgmentLogPage judgments(
            @PathVariable UUID botId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "50") int limit) {
        return queryService.getJudgments(botId, afterSequence, limit);
    }
}
