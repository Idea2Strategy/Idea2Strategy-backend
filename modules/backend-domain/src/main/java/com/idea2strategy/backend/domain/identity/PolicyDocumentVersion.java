package com.idea2strategy.backend.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PolicyDocumentVersion(
        UUID id,
        String policyCode,
        String version,
        String languageCode,
        String title,
        String contentFormat,
        String contentText,
        String contentHash,
        boolean required,
        Instant publishedAt,
        Instant retiredAt) {
    public PolicyDocumentVersion {
        Objects.requireNonNull(id, "id");
        requireText(policyCode, "policyCode");
        requireText(version, "version");
        AccountPreferences.requireSupportedLanguage(languageCode);
        requireText(title, "title");
        requireText(contentFormat, "contentFormat");
        Objects.requireNonNull(contentText, "contentText");
        requireText(contentHash, "contentHash");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (retiredAt != null && retiredAt.isBefore(publishedAt)) {
            throw new IllegalArgumentException("retiredAt must not be before publishedAt");
        }
    }

    public boolean isCurrentAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !publishedAt.isAfter(instant) && (retiredAt == null || retiredAt.isAfter(instant));
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
