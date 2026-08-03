package com.idea2strategy.backend.application.operatorrbac;

import java.util.Objects;
import java.util.UUID;

public sealed interface OperatorRbacReadResult {
    UUID correlationId();

    record Self(OperatorRbacReadModels.SelfView view, UUID correlationId)
            implements OperatorRbacReadResult {
        public Self { Objects.requireNonNull(view); Objects.requireNonNull(correlationId); }
    }

    record Catalog(OperatorRbacReadModels.CatalogView view, UUID correlationId)
            implements OperatorRbacReadResult {
        public Catalog { Objects.requireNonNull(view); Objects.requireNonNull(correlationId); }
    }

    record Assignments(OperatorRbacReadModels.AssignmentsView view, UUID correlationId)
            implements OperatorRbacReadResult {
        public Assignments { Objects.requireNonNull(view); Objects.requireNonNull(correlationId); }
    }
}
