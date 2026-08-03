package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OperatorRbacReadPort {
    OperatorRbacReadModels.ActorState loadActorState(UUID actorId, Instant evaluatedAt);

    Optional<OperatorRbacReadModels.CatalogView> loadCatalog(
            String catalogVersion, Instant evaluatedAt);

    Optional<OperatorRbacReadModels.AssignmentsView> loadAssignments(
            UUID targetOperatorId, String catalogVersion, Instant evaluatedAt);

    void recordDecision(OperatorRbacReadModels.AuditDecision decision);
}
