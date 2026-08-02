package com.idea2strategy.backend.domain.identity;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

public record AccountPreferences(
        String languageCode,
        String timezoneName,
        ThemePreference themePreference,
        Instant updatedAt) {
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("ko", "en");
    private static final Set<String> IANA_TIMEZONES = ZoneId.getAvailableZoneIds();

    public AccountPreferences {
        requireSupportedLanguage(languageCode);
        requireIanaTimezone(timezoneName);
        Objects.requireNonNull(themePreference, "themePreference");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static String requireSupportedLanguage(String languageCode) {
        Objects.requireNonNull(languageCode, "languageCode");
        if (!SUPPORTED_LANGUAGES.contains(languageCode)) {
            throw new IllegalArgumentException("Unsupported language code: " + languageCode);
        }
        return languageCode;
    }

    public static String requireIanaTimezone(String timezoneName) {
        Objects.requireNonNull(timezoneName, "timezoneName");
        try {
            ZoneId.of(timezoneName);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Unsupported IANA timezone: " + timezoneName, exception);
        }
        if (!IANA_TIMEZONES.contains(timezoneName)) {
            throw new IllegalArgumentException("Unsupported IANA timezone: " + timezoneName);
        }
        return timezoneName;
    }
}
