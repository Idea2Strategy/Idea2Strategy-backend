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
@SpringBootTest(classes = ScoringTemplateCatalogPersistenceIntegrationTest.TestApplication.class)
class ScoringTemplateCatalogPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-01T14:00:00Z");
    private static final UUID ACTIVE_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID FUTURE_ID = UUID.fromString("61000000-0000-4000-8000-000000000002");
    private static final UUID RETIRED_ID = UUID.fromString("61000000-0000-4000-8000-000000000003");

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
    private ScoringTemplateCatalogJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareCatalogVersions() {
        jdbcTemplate.update(
                "delete from competition.scoring_template_versions where id in (?, ?, ?)",
                ACTIVE_ID,
                FUTURE_ID,
                RETIRED_ID);
        insert(
                ACTIVE_ID,
                "TOTAL_RETURN",
                "1.0.0",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1",
                NOW.minusSeconds(60),
                null);
        insert(
                FUTURE_ID,
                "FUTURE_TEMPLATE",
                "1.0.0",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2",
                NOW.plusSeconds(60),
                null);
        insert(
                RETIRED_ID,
                "RETIRED_TEMPLATE",
                "1.0.0",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3",
                NOW.minusSeconds(120),
                NOW.minusSeconds(1));
    }

    @Test
    void returnsOnlyPublishedAndNonRetiredVersionsAtTheObservedInstant() {
        var selectable = adapter.findSelectableAt(NOW);

        assertThat(selectable).extracting(record -> record.id()).contains(ACTIVE_ID).doesNotContain(FUTURE_ID, RETIRED_ID);
        assertThat(adapter.findSelectableById(ACTIVE_ID, NOW)).isPresent();
        assertThat(adapter.findSelectableById(FUTURE_ID, NOW)).isEmpty();
        assertThat(adapter.findSelectableById(RETIRED_ID, NOW)).isEmpty();
    }

    private void insert(
            UUID id,
            String code,
            String version,
            String hash,
            Instant publishedAt,
            Instant retiredAt) {
        jdbcTemplate.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at, retired_at) "
                        + "values (?, ?, ?, '{}'::jsonb, ?, ?, ?)",
                id,
                code,
                version,
                hash,
                publishedAt.atOffset(ZoneOffset.UTC),
                retiredAt == null ? null : retiredAt.atOffset(ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ScoringTemplateCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}
