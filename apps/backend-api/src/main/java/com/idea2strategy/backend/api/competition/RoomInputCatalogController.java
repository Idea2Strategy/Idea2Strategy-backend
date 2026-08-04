package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.RoomExecutionPolicyCatalog;
import com.idea2strategy.backend.application.competition.RoomInputCatalogQueryService;
import com.idea2strategy.backend.domain.competition.ScoringAdjustmentDefinition;
import com.idea2strategy.backend.domain.competition.ScoringComponent;
import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/room-input-catalog")
@ConditionalOnBean(RoomInputCatalogQueryService.class)
public class RoomInputCatalogController {
    private final RoomInputCatalogQueryService queryService;

    public RoomInputCatalogController(RoomInputCatalogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public Response getSelectable() {
        var catalog = queryService.getSelectable();
        return new Response(
                catalog.scoringTemplates().stream().map(RoomInputCatalogController::response).toList(),
                catalog.feePolicies(),
                catalog.buyingPowerBufferPolicies());
    }

    private static ScoringTemplateResponse response(ScoringTemplateVersion template) {
        return new ScoringTemplateResponse(
                template.id(),
                template.templateCode(),
                template.version(),
                template.kind().name(),
                template.calculationRulesVersion(),
                template.components().stream().map(RoomInputCatalogController::response).toList(),
                template.adjustmentDefinitions().stream().map(RoomInputCatalogController::response).toList(),
                template.rulesHash());
    }

    private static ScoringComponentResponse response(ScoringComponent component) {
        return new ScoringComponentResponse(
                component.metric().name(), component.direction().name(), component.coefficient());
    }

    private static ScoringAdjustmentResponse response(ScoringAdjustmentDefinition adjustment) {
        return new ScoringAdjustmentResponse(
                adjustment.code(),
                adjustment.unit().name(),
                adjustment.minimum(),
                adjustment.maximum(),
                adjustment.scale());
    }

    public record Response(
            List<ScoringTemplateResponse> scoringTemplates,
            List<RoomExecutionPolicyCatalog.FeePolicyVersion> feePolicies,
            List<RoomExecutionPolicyCatalog.BuyingPowerBufferPolicyVersion> buyingPowerBufferPolicies) {}

    public record ScoringTemplateResponse(
            UUID id,
            String templateCode,
            String version,
            String kind,
            String calculationRulesVersion,
            List<ScoringComponentResponse> components,
            List<ScoringAdjustmentResponse> adjustments,
            String rulesHash) {}

    public record ScoringComponentResponse(String metric, String direction, BigDecimal coefficient) {}

    public record ScoringAdjustmentResponse(
            String code, String unit, BigDecimal minimum, BigDecimal maximum, int scale) {}
}
