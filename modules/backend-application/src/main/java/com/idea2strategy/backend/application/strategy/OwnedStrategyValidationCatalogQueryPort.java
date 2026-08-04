package com.idea2strategy.backend.application.strategy;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface OwnedStrategyValidationCatalogQueryPort {
    List<OwnedStrategyValidationCatalogItem> findCurrentValidOwnedBy(UUID ownerAccountId);
}
