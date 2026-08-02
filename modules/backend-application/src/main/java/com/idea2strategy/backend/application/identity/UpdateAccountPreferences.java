package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record UpdateAccountPreferences(
        String languageCode,
        String timezoneName,
        String themePreference,
        UUID correlationId) {
    public UpdateAccountPreferences {
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
