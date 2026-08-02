package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.AccountPreferenceDefaults;
import com.idea2strategy.backend.domain.identity.PolicyDocumentVersion;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityPreferenceDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void acceptsSupportedLanguagesIanaTimezonesAndThemes() {
        var preferences = new AccountPreferences("ko", "America/New_York", ThemePreference.SYSTEM, NOW);

        assertThat(preferences.languageCode()).isEqualTo("ko");
        assertThat(preferences.timezoneName()).isEqualTo("America/New_York");
        assertThat(preferences.themePreference()).isEqualTo(ThemePreference.SYSTEM);
    }

    @Test
    void rejectsUnsupportedLanguage() {
        assertThatThrownBy(() -> new AccountPreferences("ja", "Asia/Tokyo", ThemePreference.LIGHT, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
    }

    @Test
    void rejectsNonIanaTimezone() {
        assertThatThrownBy(() -> new AccountPreferences("en", "US Eastern Time", ThemePreference.DARK, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timezone");
    }

    @Test
    void requiresATheme() {
        assertThatThrownBy(() -> new AccountPreferences("en", "UTC", null, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("themePreference");
    }

    @Test
    void newAccountDefaultsAlwaysUseTheSystemTheme() {
        assertThatThrownBy(() -> new AccountPreferenceDefaults("ko", "America/New_York", ThemePreference.DARK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SYSTEM");
    }

    @Test
    void policyIsCurrentOnlyAfterPublicationAndBeforeRetirement() {
        assertThat(document(NOW.minusSeconds(60), null).isCurrentAt(NOW)).isTrue();
        assertThat(document(NOW.plusSeconds(60), null).isCurrentAt(NOW)).isFalse();
        assertThat(document(NOW.minusSeconds(120), NOW.minusSeconds(1)).isCurrentAt(NOW)).isFalse();
    }

    private static PolicyDocumentVersion document(Instant publishedAt, Instant retiredAt) {
        return new PolicyDocumentVersion(
                UUID.randomUUID(),
                "TERMS",
                "2026-08",
                "en",
                "Terms",
                "text/markdown",
                "Terms body",
                "sha256:terms",
                true,
                publishedAt,
                retiredAt);
    }
}
