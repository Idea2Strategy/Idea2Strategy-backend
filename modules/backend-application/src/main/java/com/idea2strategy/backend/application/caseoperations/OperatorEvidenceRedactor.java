package com.idea2strategy.backend.application.caseoperations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class OperatorEvidenceRedactor {
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "summaryCode", "policyVersion", "capturedAt", "contentHash", "retentionCategory");

    public List<OperatorEvidenceView> redact(List<OperatorCaseState.Evidence> evidence) {
        return evidence.stream().map(item -> new OperatorEvidenceView(
                        item.evidenceId(),
                        item.kind(),
                        item.status(),
                        item.sourceDomain(),
                        item.ownershipVerified(),
                        item.linkedAt(),
                        item.attributes().entrySet().stream()
                                .filter(entry -> ALLOWED_ATTRIBUTES.contains(entry.getKey()))
                                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue))))
                .toList();
    }
}
