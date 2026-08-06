package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.BasicStrategyValidationCommandService;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies/{strategyId}/validations")
@ConditionalOnBean({BasicStrategyValidationCommandService.class, BasicStrategyCatalogQueryService.class})
public class StrategyValidationController {
    private final BasicStrategyValidationCommandService validationService;
    private final BasicStrategyCatalogQueryService catalogService;

    public StrategyValidationController(
            BasicStrategyValidationCommandService validationService,
            BasicStrategyCatalogQueryService catalogService) {
        this.validationService = validationService;
        this.catalogService = catalogService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ValidationResponse validate(
            @PathVariable UUID strategyId,
            @RequestBody ValidationRequest request) {
        if (request.catalogId() == null) {
            throw new IllegalArgumentException("catalogId is required");
        }
        var catalog = catalogService.getPublished(request.catalogId());
        var coverage = new BacktestDataCoverage(
                catalog.version().dataRequirementVersion(), Set.of(), Set.of());
        var run = validationService.validate(strategyId, catalog, coverage);
        return new ValidationResponse(
                run.id(),
                run.strategyId(),
                run.status().name(),
                run.requestedEditSequence(),
                run.semanticHash(),
                run.elementCatalogVersionId(),
                run.findings(),
                run.completedAt());
    }

    public record ValidationRequest(UUID catalogId) {}

    public record ValidationResponse(
            UUID validationRunId,
            UUID strategyId,
            String status,
            long requestedEditSequence,
            String semanticHash,
            UUID elementCatalogVersionId,
            List<StrategyValidationFinding> findings,
            Instant completedAt) {}
}
