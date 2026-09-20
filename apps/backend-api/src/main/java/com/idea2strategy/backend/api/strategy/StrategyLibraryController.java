package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.StrategyLibraryPage;
import com.idea2strategy.backend.application.strategy.StrategyLibraryItemKind;
import com.idea2strategy.backend.application.strategy.StrategyLibraryQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies")
@ConditionalOnBean(StrategyLibraryQueryService.class)
public class StrategyLibraryController {
    private final StrategyLibraryQueryService queryService;

    public StrategyLibraryController(StrategyLibraryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public StrategyLibraryPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String kind) {
        return queryService.list(cursor, limit, StrategyLibraryItemKind.fromWireValue(kind));
    }
}
