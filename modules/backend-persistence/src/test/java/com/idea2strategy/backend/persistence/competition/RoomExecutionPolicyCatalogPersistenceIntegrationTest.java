package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;

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
@SpringBootTest(classes = RoomExecutionPolicyCatalogPersistenceIntegrationTest.TestApplication.class)
class RoomExecutionPolicyCatalogPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    private static final UUID ACTIVE_FEE = UUID.fromString("73000000-0000-4000-8000-000000000001");
    private static final UUID FUTURE_FEE = UUID.fromString("73000000-0000-4000-8000-000000000002");
    private static final UUID ACTIVE_BUFFER = UUID.fromString("73000000-0000-4000-8000-000000000003");
    private static final UUID EXPIRED_BUFFER = UUID.fromString("73000000-0000-4000-8000-000000000004");

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
    private RoomExecutionPolicyCatalogJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void preparePolicies() {
        jdbc.update("delete from trading.fee_policy_versions where id in (?, ?)", ACTIVE_FEE, FUTURE_FEE);
        jdbc.update(
                "delete from trading.buying_power_buffer_policy_versions where id in (?, ?)",
                ACTIVE_BUFFER,
                EXPIRED_BUFFER);
        insertFee(ACTIVE_FEE, "ACTIVE_FEE", NOW.minusSeconds(120), NOW.minusSeconds(60), null, 'a');
        insertFee(FUTURE_FEE, "FUTURE_FEE", NOW.minusSeconds(120), NOW.plusSeconds(60), null, 'b');
        insertBuffer(ACTIVE_BUFFER, "ACTIVE_BUFFER", NOW.minusSeconds(120), NOW.minusSeconds(60), null, 'c');
        insertBuffer(EXPIRED_BUFFER, "EXPIRED_BUFFER", NOW.minusSeconds(120), NOW.minusSeconds(60),
                NOW, 'd');
    }

    @Test
    void returnsOnlyPublishedAndCurrentlyEffectivePolicies() {
        var catalog = adapter.findSelectableAt(NOW);

        assertThat(catalog.feePolicies()).extracting(policy -> policy.id())
                .containsExactly(ACTIVE_FEE);
        assertThat(catalog.buyingPowerBufferPolicies()).extracting(policy -> policy.id())
                .containsExactly(ACTIVE_BUFFER);
    }

    private void insertFee(
            UUID id, String code, Instant publishedAt, Instant effectiveFrom, Instant effectiveTo, char hash) {
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, effective_to, published_at) values (?, ?, '1.0.0', 20, '1.0.0', ?, ?, ?, ?)",
                id,
                code,
                String.valueOf(hash).repeat(64),
                effectiveFrom.atOffset(ZoneOffset.UTC),
                effectiveTo == null ? null : effectiveTo.atOffset(ZoneOffset.UTC),
                publishedAt.atOffset(ZoneOffset.UTC));
    }

    private void insertBuffer(
            UUID id, String code, Instant publishedAt, Instant effectiveFrom, Instant effectiveTo, char hash) {
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, effective_to, published_at) values (?, ?, '1.0.0', 100, '1.0.0', ?, ?, ?, ?)",
                id,
                code,
                String.valueOf(hash).repeat(64),
                effectiveFrom.atOffset(ZoneOffset.UTC),
                effectiveTo == null ? null : effectiveTo.atOffset(ZoneOffset.UTC),
                publishedAt.atOffset(ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(RoomExecutionPolicyCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}
