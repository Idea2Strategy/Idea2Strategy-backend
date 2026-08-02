package com.idea2strategy.backend.domain.identity;

import java.time.Instant;
import java.util.Objects;

public record AccountPreferenceDefaults(
        String languageCode,
        String timezoneName,
        ThemePreference themePreference) {
    public AccountPreferenceDefaults {
        AccountPreferences.requireSupportedLanguage(languageCode);
        AccountPreferences.requireIanaTimezone(timezoneName);
        Objects.requireNonNull(themePreference, "themePreference");
        if (themePreference != ThemePreference.SYSTEM) {
            throw new IllegalArgumentException("New accounts must default to the SYSTEM theme");
        }
    }

    public AccountPreferences at(Instant updatedAt) {
        return new AccountPreferences(languageCode, timezoneName, themePreference, updatedAt);
    }
}
