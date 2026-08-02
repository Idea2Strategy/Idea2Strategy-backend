package com.idea2strategy.backend.application.usercase;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record UserCaseCommand(
        UUID accountId,
        UserCaseType type,
        String subject,
        String description,
        List<UserCaseEvidenceReference> evidenceReferences,
        String idempotencyKey,
        String requestHash,
        UUID correlationId) {
    public UserCaseCommand {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        subject = requireText(subject, "subject");
        description = requireText(description, "description");
        evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        requestHash = requireText(requestHash, "requestHash");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
