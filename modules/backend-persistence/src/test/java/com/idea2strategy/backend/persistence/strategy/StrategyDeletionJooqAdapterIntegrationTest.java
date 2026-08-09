package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.StrategyDeletionResult;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
@SpringBootTest(classes = StrategyDeletionJooqAdapterIntegrationTest.TestApplication.class)
class StrategyDeletionJooqAdapterIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private StrategyDeletionJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedDraftAndLease() {
        jdbc.update("delete from strategy.strategy_edit_leases where strategy_id = ?", STRATEGY_ID);
        jdbc.update("delete from strategy.strategy_documents where strategy_id = ?", STRATEGY_ID);
        jdbc.update("delete from strategy.strategies where id = ?", STRATEGY_ID);
        jdbc.execute("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);
        var now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                OWNER_ID, now, OTHER_OWNER_ID, now);
        jdbc.update(
                "insert into strategy.strategies (id, owner_account_id, mode, name, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'Disposable draft', ?, ?)",
                STRATEGY_ID, OWNER_ID, now.minusSeconds(20), now.minusSeconds(20));
        jdbc.update(
                "insert into strategy.strategy_documents (strategy_id, semantic_document, presentation_document, "
                        + "semantic_schema_version, presentation_schema_version, semantic_hash, presentation_hash, "
                        + "created_at, updated_at) values (?, '{}'::jsonb, '{}'::jsonb, '1', '1', ?, ?, ?, ?)",
                STRATEGY_ID, "a".repeat(64), "b".repeat(64), now.minusSeconds(20), now.minusSeconds(20));
        jdbc.update(
                "insert into strategy.strategy_edit_leases (strategy_id, account_id, lease_token_digest, digest_key_version, "
                        + "acquired_at, heartbeat_at, expires_at) values (?, ?, ?, 1, ?, ?, ?)",
                STRATEGY_ID, OWNER_ID, "c".repeat(64), now.minusSeconds(10), now.minusSeconds(5), now.plusSeconds(60));
    }

    @Test
    void softDeletesOnceRevokesTheLeaseAndPreservesTheDocument() {
        assertThat(adapter.deleteOwned(STRATEGY_ID, OWNER_ID, NOW)).isEqualTo(StrategyDeletionResult.DELETED);
        assertThat(adapter.deleteOwned(STRATEGY_ID, OWNER_ID, NOW.plusSeconds(1)))
                .isEqualTo(StrategyDeletionResult.ALREADY_DELETED);

        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from strategy.strategies where id = ?", Boolean.class, STRATEGY_ID))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select delegated_access_epoch from strategy.strategies where id = ?", Long.class, STRATEGY_ID))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "select count(*) from strategy.strategy_edit_leases where strategy_id = ?", Long.class, STRATEGY_ID))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from strategy.strategy_documents where strategy_id = ?", Long.class, STRATEGY_ID))
                .isEqualTo(1L);
    }

    @Test
    void doesNotRevealAnotherOwnersDraft() {
        assertThat(adapter.deleteOwned(STRATEGY_ID, OTHER_OWNER_ID, NOW))
                .isEqualTo(StrategyDeletionResult.NOT_FOUND);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(StrategyDeletionJooqAdapter.class)
    static class TestApplication {}
}
