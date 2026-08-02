package com.idea2strategy.backend.application.caseoperations;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OperatorEvidenceView(
        UUID evidenceId,
        String kind,
        String status,
        String sourceDomain,
        boolean ownershipVerified,
        Instant linkedAt,
        Map<String, Object> attributes) {
    public OperatorEvidenceView {
        attributes = Map.copyOf(attributes);
    }
}
