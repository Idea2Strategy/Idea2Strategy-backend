package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.util.List;
import java.util.Objects;

public final class OwnedStrategyValidationCatalogQueryService {
    private final OwnedStrategyValidationCatalogQueryPort queryPort;
    private final CurrentPrincipal principal;

    public OwnedStrategyValidationCatalogQueryService(
            OwnedStrategyValidationCatalogQueryPort queryPort, CurrentPrincipal principal) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public List<OwnedStrategyValidationCatalogItem> listCurrentValid() {
        return List.copyOf(queryPort.findCurrentValidOwnedBy(principal.accountId()));
    }
}
