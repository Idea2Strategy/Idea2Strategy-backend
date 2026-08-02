package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.identity.ConsentDecisionOutcome;
import com.idea2strategy.backend.domain.identity.ConsentDecision;
import com.idea2strategy.backend.persistence.identity.AccountPreferencesConsentJpaAdapter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
@SpringBootTest(classes = IdentityConsentConcurrencyPostgresIntegrationTest.TestApplication.class)
class IdentityConsentConcurrencyPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-02T06:00:00Z");

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
    private JdbcTemplate jdbc;

    @Test
    void concurrentFirstDecisionsFormOneAppendOnlyUnforkedChain() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID policyDocumentId = UUID.randomUUID();
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, cast('ACTIVE' as identity.account_lifecycle_status))",
                accountId);
        jdbc.update(
                """
                insert into identity.policy_documents
                    (id, policy_code, version, language_code, title, content_format, content_text,
                     content_hash, is_required, published_at)
                values (?, 'TERMS', '1.0.0', 'ko', 'Terms', 'text/markdown', 'immutable body',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', true, ?)
                """,
                policyDocumentId,
                NOW.minusSeconds(60).atOffset(ZoneOffset.UTC));

        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var accepted = executor.submit(() -> recordAfter(
                    start, accountId, policyDocumentId, ConsentDecision.ACCEPTED, NOW, UUID.randomUUID()));
            var withdrawn = executor.submit(() -> recordAfter(
                    start, accountId, policyDocumentId, ConsentDecision.WITHDRAWN,
                    NOW.plusMillis(1), UUID.randomUUID()));
            start.countDown();

            assertThat(List.of(accepted.get(), withdrawn.get()))
                    .allMatch(outcome -> outcome == ConsentDecisionOutcome.RECORDED);
        }

        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.account_consents where account_id = ? and policy_document_id = ?",
                        Integer.class,
                        accountId,
                        policyDocumentId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*)
                        from identity.account_consents consent
                        where consent.account_id = ? and consent.policy_document_id = ?
                          and consent.supersedes_consent_id is null
                        """,
                        Integer.class,
                        accountId,
                        policyDocumentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*)
                        from identity.account_consents consent
                        where consent.account_id = ? and consent.policy_document_id = ?
                          and not exists (
                              select 1 from identity.account_consents successor
                              where successor.supersedes_consent_id = consent.id
                          )
                        """,
                        Integer.class,
                        accountId,
                        policyDocumentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*)
                        from identity.authentication_events
                        where account_id = ? and event_type = 'POLICY_CONSENT_RECORDED'
                        """,
                        Integer.class,
                        accountId))
                .isEqualTo(2);
    }

    private ConsentDecisionOutcome recordAfter(
            CountDownLatch start,
            UUID accountId,
            UUID policyDocumentId,
            ConsentDecision decision,
            Instant recordedAt,
            UUID correlationId) throws InterruptedException {
        start.await();
        return commands.recordDecision(
                        accountId, policyDocumentId, decision, correlationId, recordedAt)
                .outcome();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AccountPreferencesConsentJpaAdapter.class)
    static class TestApplication {}
}
