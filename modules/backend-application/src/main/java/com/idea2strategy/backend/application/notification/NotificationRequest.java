package com.idea2strategy.backend.application.notification;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record NotificationRequest(
        UUID accountId,
        String typeCode,
        String templateVersion,
        String locale,
        String sourceEventId,
        String sourceEventHash,
        Map<String, String> templateArguments,
        UUID correlationId) {
    public NotificationRequest {
        Objects.requireNonNull(accountId, "accountId");
        typeCode = required(typeCode, "typeCode");
        templateVersion = required(templateVersion, "templateVersion");
        locale = required(locale, "locale");
        sourceEventId = required(sourceEventId, "sourceEventId");
        sourceEventHash = required(sourceEventHash, "sourceEventHash");
        templateArguments = Map.copyOf(templateArguments);
        Objects.requireNonNull(correlationId, "correlationId");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
