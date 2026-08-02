package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.identity.ConsentDecisionOutcome;
import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.ConsentDecision;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AccountPreferencesConsentPersistenceIntegrationTest.TestApplication.class)
class AccountPreferencesConsentPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-02T07:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private AccountPreferencesConsentJpaAdapter commands;

    @Autowired
    private AccountPreferencesConsentJooqAdapter queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void updatesOwnedPreferencesAndReturnsOnlyDeterministicCurrentPolicies() {
        UUID accountId = activeAccount();
        jdbc.update("""
                insert into identity.account_preferences
                    (account_id, language_code, timezone_name, theme_preference, created_at, updated_at)
                values (?, 'ko', 'America/New_York', cast('SYSTEM' as identity.theme_preference), ?, ?)
                """, accountId, utc(NOW.minusSeconds(120)), utc(NOW.minusSeconds(120)));

        var requested = new AccountPreferences("en", "America/Chicago", ThemePreference.DARK, NOW);
        assertThat(commands.update(accountId, requested, UUID.randomUUID())).isEqualTo(requested);
        assertThat(queries.findByAccountId(accountId)).contains(requested);

        UUID oldTerms = policy("TERMS", "1.0.0", "ko", NOW.minusSeconds(300), null);
        UUID currentTerms = policy("TERMS", "2.0.0", "ko", NOW.minusSeconds(60), null);
        policy("TERMS", "3.0.0", "ko", NOW.plusSeconds(60), null);
        policy("PRIVACY", "1.0.0", "ko", NOW.minusSeconds(300), NOW.minusSeconds(1));
        policy("DISCLOSURE", "1.0.0", "ko", NOW.minusSeconds(300), NOW.plusSeconds(60));
        policy("TERMS", "9.0.0", "en", NOW.minusSeconds(1), null);

        assertThat(queries.findCurrentPolicies("ko", NOW))
                .extracting(policy -> policy.id())
                .containsExactly(currentTerms)
                .doesNotContain(oldTerms);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'ACCOUNT_PREFERENCES_UPDATED'",
                        Integer.class,
                        accountId))
                .isEqualTo(1);
    }

    @Test
    void appendsConsentHistoryAndAuditsARejectedNonCurrentPolicy() {
        UUID accountId = activeAccount();
        UUID oldPolicy = policy("CONSENT_TERMS", "1.0.0", "ko", NOW.minusSeconds(120), null);
        UUID currentPolicy = policy("CONSENT_TERMS", "2.0.0", "ko", NOW.minusSeconds(60), null);
        UUID futurePolicy = policy("CONSENT_PRIVACY", "1.0.0", "ko", NOW.plusSeconds(60), null);

        var supersededVersion = commands.recordDecision(
                accountId, oldPolicy, ConsentDecision.ACCEPTED, UUID.randomUUID(), NOW);
        var first = commands.recordDecision(
                accountId, currentPolicy, ConsentDecision.ACCEPTED, UUID.randomUUID(), NOW);
        var second = commands.recordDecision(
                accountId, currentPolicy, ConsentDecision.WITHDRAWN, UUID.randomUUID(), NOW.plusMillis(1));
        var rejected = commands.recordDecision(
                accountId, futurePolicy, ConsentDecision.ACCEPTED, UUID.randomUUID(), NOW);

        assertThat(supersededVersion.outcome()).isEqualTo(ConsentDecisionOutcome.POLICY_NOT_CURRENT);
        assertThat(first.outcome()).isEqualTo(ConsentDecisionOutcome.RECORDED);
        assertThat(second.outcome()).isEqualTo(ConsentDecisionOutcome.RECORDED);
        assertThat(second.consent().orElseThrow().supersedesConsentId())
                .isEqualTo(first.consent().orElseThrow().id());
        assertThat(rejected.outcome()).isEqualTo(ConsentDecisionOutcome.POLICY_NOT_CURRENT);
        assertThat(rejected.consent()).isEmpty();
        assertThat(queries.findLatestConsent(accountId, currentPolicy)).contains(second.consent().orElseThrow());
        assertThat(queries.findConsentHistory(accountId, currentPolicy))
                .containsExactly(first.consent().orElseThrow(), second.consent().orElseThrow());
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'POLICY_CONSENT_REJECTED' and reason_code = 'POLICY_NOT_CURRENT'",
                        Integer.class,
                        accountId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.account_consents where policy_document_id = ?",
                        Integer.class,
                        oldPolicy))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.account_consents where policy_document_id = ?",
                        Integer.class,
                        futurePolicy))
                .isZero();
    }

    private UUID activeAccount() {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, cast('ACTIVE' as identity.account_lifecycle_status))",
                accountId);
        return accountId;
    }

    private UUID policy(
            String code, String version, String language, Instant publishedAt, Instant retiredAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into identity.policy_documents
                    (id, policy_code, version, language_code, title, content_format, content_text,
                     content_hash, is_required, published_at, retired_at)
                values (?, ?, ?, ?, ?, 'text/markdown', 'immutable body', ?, true, ?, ?)
                """,
                id,
                code,
                version,
                language,
                code + " " + version,
                "hash:" + id,
                utc(publishedAt),
                retiredAt == null ? null : utc(retiredAt));
        return id;
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AccountPreferencesConsentJpaAdapter.class, AccountPreferencesConsentJooqAdapter.class})
    static class TestApplication {}
}
