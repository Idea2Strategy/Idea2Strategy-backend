package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class RoomInputCatalogQueryService {
    private final ScoringTemplateCatalogService scoringCatalog;
    private final RoomExecutionPolicyCatalogQueryPort executionPolicies;
    private final Clock clock;

    public RoomInputCatalogQueryService(
            ScoringTemplateCatalogService scoringCatalog,
            RoomExecutionPolicyCatalogQueryPort executionPolicies,
            Clock clock) {
        this.scoringCatalog = Objects.requireNonNull(scoringCatalog, "scoringCatalog");
        this.executionPolicies = Objects.requireNonNull(executionPolicies, "executionPolicies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RoomInputCatalog getSelectable() {
        var policies = executionPolicies.findSelectableAt(clock.instant());
        return new RoomInputCatalog(
                scoringCatalog.listSelectable(),
                policies.feePolicies(),
                policies.buyingPowerBufferPolicies());
    }

    public record RoomInputCatalog(
            List<ScoringTemplateVersion> scoringTemplates,
            List<RoomExecutionPolicyCatalog.FeePolicyVersion> feePolicies,
            List<RoomExecutionPolicyCatalog.BuyingPowerBufferPolicyVersion> buyingPowerBufferPolicies) {
        public RoomInputCatalog {
            scoringTemplates = List.copyOf(scoringTemplates);
            feePolicies = List.copyOf(feePolicies);
            buyingPowerBufferPolicies = List.copyOf(buyingPowerBufferPolicies);
        }
    }
}
