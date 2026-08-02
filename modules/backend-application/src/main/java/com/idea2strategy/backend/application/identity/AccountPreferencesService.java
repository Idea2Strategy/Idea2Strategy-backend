package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class AccountPreferencesService {
    private final AccountPreferencesQueryPort queries;
    private final AccountPreferencesCommandPort commands;
    private final Clock clock;

    public AccountPreferencesService(
            AccountPreferencesQueryPort queries,
            AccountPreferencesCommandPort commands,
            Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AccountPreferences get(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return queries.findByAccountId(accountId).orElseThrow(AccountPreferencesNotFoundException::new);
    }

    public AccountPreferences update(UUID accountId, UpdateAccountPreferences update) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(update, "update");
        var now = clock.instant();
        AccountPreferences preferences;
        try {
            preferences = new AccountPreferences(
                    update.languageCode(),
                    update.timezoneName(),
                    ThemePreference.valueOf(Objects.requireNonNull(update.themePreference(), "themePreference")),
                    now);
        } catch (IllegalArgumentException | NullPointerException exception) {
            commands.recordPreferenceRejection(
                    accountId,
                    "INVALID_ACCOUNT_PREFERENCES",
                    update.correlationId(),
                    now);
            throw new IllegalArgumentException("Invalid account preferences", exception);
        }
        return commands.update(accountId, preferences, update.correlationId());
    }
}
