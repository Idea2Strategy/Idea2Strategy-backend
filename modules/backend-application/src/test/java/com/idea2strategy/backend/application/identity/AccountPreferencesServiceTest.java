package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountPreferencesServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Test
    void updatesOnlyTheAuthenticatedAccountsValidatedDisplayPreferences() {
        var repository = new Repository();
        var service = service(repository);
        UUID correlationId = UUID.randomUUID();

        var updated = service.update(
                ACCOUNT_ID,
                new UpdateAccountPreferences("en", "America/Chicago", "DARK", correlationId));

        assertThat(repository.updatedAccountId).isEqualTo(ACCOUNT_ID);
        assertThat(repository.correlationId).isEqualTo(correlationId);
        assertThat(updated).isEqualTo(new AccountPreferences("en", "America/Chicago", ThemePreference.DARK, NOW));
    }

    @Test
    void auditsInvalidPreferencesWithoutCallingTheUpdateCommand() {
        var repository = new Repository();
        var service = service(repository);
        UUID correlationId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(
                        ACCOUNT_ID,
                        new UpdateAccountPreferences("fr", "Europe/Paris", "LIGHT", correlationId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.updatedAccountId).isNull();
        assertThat(repository.rejectedAccountId).isEqualTo(ACCOUNT_ID);
        assertThat(repository.rejectionReason).isEqualTo("INVALID_ACCOUNT_PREFERENCES");
        assertThat(repository.correlationId).isEqualTo(correlationId);
    }

    @Test
    void auditsAnInvalidOrMissingThemeBeforeReturningAClientError() {
        var repository = new Repository();
        var service = service(repository);

        assertThatThrownBy(() -> service.update(
                        ACCOUNT_ID,
                        new UpdateAccountPreferences("en", "America/Chicago", null, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.rejectionReason).isEqualTo("INVALID_ACCOUNT_PREFERENCES");
    }

    @Test
    void reportsAMissingPreferenceRecord() {
        var repository = new Repository();

        assertThatThrownBy(() -> service(repository).get(ACCOUNT_ID))
                .isInstanceOf(AccountPreferencesNotFoundException.class);
    }

    private static AccountPreferencesService service(Repository repository) {
        return new AccountPreferencesService(repository, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class Repository implements AccountPreferencesQueryPort, AccountPreferencesCommandPort {
        private AccountPreferences stored;
        private UUID updatedAccountId;
        private UUID rejectedAccountId;
        private String rejectionReason;
        private UUID correlationId;

        @Override
        public Optional<AccountPreferences> findByAccountId(UUID accountId) {
            return Optional.ofNullable(stored);
        }

        @Override
        public AccountPreferences update(UUID accountId, AccountPreferences preferences, UUID correlationId) {
            this.updatedAccountId = accountId;
            this.correlationId = correlationId;
            this.stored = preferences;
            return preferences;
        }

        @Override
        public void recordPreferenceRejection(
                UUID accountId, String reasonCode, UUID correlationId, Instant occurredAt) {
            this.rejectedAccountId = accountId;
            this.rejectionReason = reasonCode;
            this.correlationId = correlationId;
        }
    }
}
