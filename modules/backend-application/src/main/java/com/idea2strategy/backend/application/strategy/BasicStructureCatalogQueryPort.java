package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BasicStructureCatalogQueryPort {
    List<BasicStructureCandidate> findActivePublishedByCatalogId(UUID catalogId, Instant publishedAt);
}
