package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoringTemplateCatalogQueryPort {
    List<ScoringTemplateCatalogRecord> findSelectableAt(Instant at);

    Optional<ScoringTemplateCatalogRecord> findSelectableById(UUID id, Instant at);
}
