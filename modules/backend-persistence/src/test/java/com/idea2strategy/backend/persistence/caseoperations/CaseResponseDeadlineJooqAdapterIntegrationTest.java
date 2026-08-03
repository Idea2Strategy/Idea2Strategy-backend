package com.idea2strategy.backend.persistence.caseoperations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.caseoperations.CaseResponseDeadlinePort;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CaseResponseDeadlineJooqAdapterIntegrationTest.TestApplication.class)
class CaseResponseDeadlineJooqAdapterIntegrationTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID CASE_ID = id(2);
    private static final Instant DEADLINE = Instant.parse("2020-01-01T00:00:00Z");

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

    @Autowired CaseResponseDeadlineJooqAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void prepare() {
        jdbc.execute("truncate table operations.case_deadline_receipts, operations.case_command_receipts, "
                + "operations.case_evidence_references, operations.case_events, operations.cases cascade");
        jdbc.update("delete from operations.outbox_messages where owner_domain = 'OPERATIONS_CASE'");
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE') "
                + "on conflict (id) do update set lifecycle_status = 'ACTIVE'", ACCOUNT);
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> seedCase());
    }

    @Test
    void appliesExactlyOnceAndPersistsAStaleIdentityAsAlreadyTransitioned() {
        CaseResponseDeadlinePort.Identity identity = adapter.findDue(10).getFirst();
        UUID correlation = UUID.randomUUID();

        CaseResponseDeadlinePort.Result first = adapter.expire(identity, correlation);
        CaseResponseDeadlinePort.Result replay = adapter.expire(identity, UUID.randomUUID());
        CaseResponseDeadlinePort.Result stale = adapter.expire(
                new CaseResponseDeadlinePort.Identity(CASE_ID, 1, DEADLINE), UUID.randomUUID());

        assertThat(first.status()).isEqualTo(CaseResponseDeadlinePort.Result.Status.APPLIED);
        assertThat(replay).isEqualTo(first);
        assertThat(stale.status()).isEqualTo(CaseResponseDeadlinePort.Result.Status.ALREADY_TRANSITIONED);
        assertThat(text("select status::text from operations.cases where id = ?", CASE_ID))
                .isEqualTo("UNDER_REVIEW");
        assertThat(count("select count(*) from operations.case_events where case_id = ? "
                + "and event_type = 'INFORMATION_RESPONSE_DEADLINE_EXPIRED'", CASE_ID)).isOne();
        assertThat(count("select count(*) from operations.outbox_messages where aggregate_id = ? "
                + "and event_type = 'INFORMATION_RESPONSE_DEADLINE_EXPIRED'", CASE_ID)).isOne();
        assertThat(count("select count(*) from operations.case_deadline_receipts where case_id = ?", CASE_ID))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select response_deadline_at is null from operations.cases where id = ?",
                Boolean.class, CASE_ID)).isTrue();
    }

    private void seedCase() {
        jdbc.execute("set constraints all deferred");
        UUID submitted = id(3);
        UUID requested = id(4);
        var created = Instant.parse("2019-12-01T00:00:00Z").atOffset(ZoneOffset.UTC);
        jdbc.update("""
                insert into operations.cases
                    (id, account_id, case_type, status, subject, case_version,
                     current_event_sequence, last_case_event_id, response_deadline_at,
                     deadline_policy_version, created_at, updated_at)
                values (?, ?, 'REPORT', 'NEEDS_INFORMATION', 'deadline-test', 2, 2, ?, ?,
                        'case-response-v1', ?, ?)
                """, CASE_ID, ACCOUNT, requested, DEADLINE.atOffset(ZoneOffset.UTC), created, created);
        jdbc.update("""
                insert into operations.case_events
                    (id, case_id, account_id, event_sequence, previous_event_id, actor_type,
                     actor_id, event_type, resulting_status, visibility, correlation_id,
                     payload_document, created_at)
                values (?, ?, ?, 1, null, 'ACCOUNT', ?, 'SUBMITTED', 'OPEN', 'USER_VISIBLE',
                        ?, '{}'::jsonb, ?)
                """, submitted, CASE_ID, ACCOUNT, ACCOUNT, UUID.randomUUID(), created);
        jdbc.update("""
                insert into operations.case_events
                    (id, case_id, account_id, event_sequence, previous_event_id, actor_type,
                     actor_id, event_type, resulting_status, visibility, correlation_id,
                     payload_document, created_at)
                values (?, ?, ?, 2, ?, 'SYSTEM', ?, 'INFORMATION_REQUESTED',
                        'NEEDS_INFORMATION', 'USER_VISIBLE', ?, '{}'::jsonb, ?)
                """, requested, CASE_ID, ACCOUNT, submitted, ACCOUNT, UUID.randomUUID(), created);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2210000-0000-4000-8000-%012d".formatted(suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(CaseResponseDeadlineJooqAdapter.class)
    static class TestApplication {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
