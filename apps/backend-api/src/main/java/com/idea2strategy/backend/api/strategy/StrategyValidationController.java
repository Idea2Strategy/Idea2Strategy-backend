package com.idea2strategy.backend.api.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BacktestDataCoverage;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.BasicStrategyValidationCommandService;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1/strategies/{strategyId}")
@ConditionalOnBean({BasicStrategyValidationCommandService.class, BasicStrategyCatalogQueryService.class})
public class StrategyValidationController {
    private final BasicStrategyValidationCommandService validationService;
    private final BasicStrategyCatalogQueryService catalogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StrategyValidationController(
            BasicStrategyValidationCommandService validationService,
            BasicStrategyCatalogQueryService catalogService) {
        this.validationService = validationService;
        this.catalogService = catalogService;
    }

    @PostMapping("/validations")
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
        return response(run);
    }

    @PostMapping("/validation-previews")
    public ValidationResponse preview(
            @PathVariable UUID strategyId,
            @RequestBody ValidationPreviewRequest request) {
        if (request.catalogId() == null || request.semanticDocument() == null) {
            throw new IllegalArgumentException("catalogId and semanticDocument are required");
        }
        if (request.clientRevision() < 0) {
            throw new IllegalArgumentException("clientRevision must not be negative");
        }
        var catalog = catalogService.getPublished(request.catalogId());
        var coverage = new BacktestDataCoverage(
                catalog.version().dataRequirementVersion(), Set.of(), Set.of());
        var run = validationService.preview(
                strategyId,
                catalog,
                coverage,
                writeJson(request.semanticDocument()),
                request.clientRevision());
        return response(run);
    }

    private ValidationResponse response(com.idea2strategy.backend.domain.strategy.StrategyValidationRun run) {
        return new ValidationResponse(
                run.id(), run.strategyId(), run.status().name(), run.requestedEditSequence(),
                run.semanticHash(), run.elementCatalogVersionId(), run.findings(), run.completedAt());
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("semanticDocument could not be serialized", exception);
        }
    }

    public record ValidationRequest(UUID catalogId) {}

    public record ValidationPreviewRequest(
            UUID catalogId,
            long clientRevision,
            Map<String, Object> semanticDocument) {
        @Override
        public String toString() {
            return "ValidationPreviewRequest[content=REDACTED]";
        }
    }

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
