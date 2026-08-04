package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogItem;
import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogQueryService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategy-validations/current")
@ConditionalOnBean(OwnedStrategyValidationCatalogQueryService.class)
public class OwnedStrategyValidationCatalogController {
    private final OwnedStrategyValidationCatalogQueryService queryService;

    public OwnedStrategyValidationCatalogController(OwnedStrategyValidationCatalogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public Response listCurrentValid() {
        return new Response(queryService.listCurrentValid());
    }

    public record Response(List<OwnedStrategyValidationCatalogItem> items) {}
}
