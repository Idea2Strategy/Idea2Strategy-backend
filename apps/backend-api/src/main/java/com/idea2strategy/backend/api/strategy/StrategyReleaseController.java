package com.idea2strategy.backend.api.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommand;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandService;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies/{strategyId}/releases")
@ConditionalOnBean({ImmutableStrategyReleaseCommandService.class, BasicStrategyCatalogQueryService.class})
public class StrategyReleaseController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ImmutableStrategyReleaseCommandService releaseService;
    private final BasicStrategyCatalogQueryService catalogService;

    public StrategyReleaseController(
            ImmutableStrategyReleaseCommandService releaseService,
            BasicStrategyCatalogQueryService catalogService) {
        this.releaseService = releaseService;
        this.catalogService = catalogService;
    }

    @PostMapping
    public ResponseEntity<ReleaseResponse> release(
            @PathVariable UUID strategyId,
            @RequestBody ReleaseRequest request) {
        var command = request.toCommand(releaseId(request.validationRunId()));
        var release = releaseService.release(strategyId, request.validationRunId(), catalogService, command);
        return ResponseEntity.created(URI.create("/api/v1/bots/" + release.botId()))
                .body(new ReleaseResponse(release.botId(), "BASIC"));
    }

    static UUID releaseId(UUID validationRunId) {
        if (validationRunId == null) {
            throw new IllegalArgumentException("validationRunId is required");
        }
        return UUID.nameUUIDFromBytes(
                ("strategy-release:" + validationRunId).getBytes(StandardCharsets.UTF_8));
    }

    public record ReleaseRequest(
            UUID validationRunId,
            BigDecimal initialCashAmount,
            int budgetCapBps,
            String brokerRulesVersion,
            String accountingRulesVersion,
            String precisionRulesVersion,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            UUID datasetManifestId,
            String executionPolicyVersion,
            Map<String, Object> candidateConflictPolicy) {
        ImmutableStrategyReleaseCommand toCommand(UUID releaseId) {
            require(initialCashAmount, "initialCashAmount");
            require(brokerRulesVersion, "brokerRulesVersion");
            require(accountingRulesVersion, "accountingRulesVersion");
            require(precisionRulesVersion, "precisionRulesVersion");
            require(feePolicyId, "feePolicyId");
            require(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
            require(datasetManifestId, "datasetManifestId");
            require(executionPolicyVersion, "executionPolicyVersion");
            require(candidateConflictPolicy, "candidateConflictPolicy");
            if (budgetCapBps <= 0 || budgetCapBps > 10_000) {
                throw new IllegalArgumentException("budgetCapBps must be in 1..10000");
            }
            return new ImmutableStrategyReleaseCommand(
                    releaseId,
                    initialCashAmount,
                    budgetCapBps,
                    brokerRulesVersion,
                    accountingRulesVersion,
                    precisionRulesVersion,
                    feePolicyId,
                    buyingPowerBufferPolicyId,
                    datasetManifestId,
                    executionPolicyVersion,
                    json(candidateConflictPolicy));
        }

        private static void require(Object value, String field) {
            if (value == null || value instanceof String text && text.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
        }

        private static String json(Map<String, Object> value) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("candidateConflictPolicy must be valid JSON", exception);
            }
        }
    }

    public record ReleaseResponse(UUID botId, String backtestLane) {}
}
