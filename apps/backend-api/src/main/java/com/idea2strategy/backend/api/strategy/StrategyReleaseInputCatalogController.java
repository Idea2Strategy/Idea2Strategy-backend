package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalogQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategy-release-inputs")
@ConditionalOnBean(StrategyReleaseInputCatalogQueryService.class)
public class StrategyReleaseInputCatalogController {
    private final StrategyReleaseInputCatalogQueryService queryService;

    public StrategyReleaseInputCatalogController(StrategyReleaseInputCatalogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public StrategyReleaseInputCatalog getSelectable() {
        return queryService.getSelectable();
    }
}
