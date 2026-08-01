package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CompetitionRoomCqrsPersistenceIntegrationTest.TestApplication.class)
class CompetitionRoomCqrsPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID SCORING_VERSION_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY_ID = UUID.fromString("52000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_POLICY_ID = UUID.fromString("53000000-0000-4000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");

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
    private CompetitionRoomJpaCommandAdapter commandAdapter;

    @Autowired
    private CompetitionRoomJooqQueryAdapter queryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareReferences() {
        jdbcTemplate.update("delete from competition.room_schedules");
        jdbcTemplate.update("delete from competition.room_rules");
        jdbcTemplate.update("delete from competition.rooms");
        jdbcTemplate.update("delete from competition.scoring_template_versions where id = ?", SCORING_VERSION_ID);
        jdbcTemplate.update("delete from trading.fee_policy_versions where id = ?", FEE_POLICY_ID);
        jdbcTemplate.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_POLICY_ID);
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                OWNER_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'v1', '{}'::jsonb, 'scoring-v1', ?)",
                SCORING_VERSION_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, effective_from, published_at) "
                        + "values (?, 'OFFICIAL', 'v1', 20, 'v1', 'fee-v1', ?, ?)",
                FEE_POLICY_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at) "
                        + "values (?, 'DEFAULT', 'v1', 0, 'v1', 'buffer-v1', ?, ?)",
                BUFFER_POLICY_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void savedRoomPreservesAccessScheduleCapitalAndScoringVersion() {
        var schedule = new RoomSchedule(
                Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-02T01:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z"),
                Instant.parse("2026-08-02T23:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T01:00:00Z"),
                "America/New_York");
        var room = CompetitionRoom.publicLive(
                ROOM_ID,
                OWNER_ID,
                "August room",
                SCORING_VERSION_ID,
                new BigDecimal("100000.00000000"),
                10,
                1,
                FEE_POLICY_ID,
                BUFFER_POLICY_ID,
                schedule,
                CREATED_AT);

        commandAdapter.save(room);
        var loaded = queryAdapter.findById(ROOM_ID).orElseThrow();

        assertThat(loaded.accessType()).isEqualTo(RoomAccessType.PUBLIC);
        assertThat(loaded.schedule()).isEqualTo(schedule);
        assertThat(loaded.initialCashAmount()).isEqualByComparingTo("100000.00000000");
        assertThat(loaded.scoringTemplateVersionId()).isEqualTo(SCORING_VERSION_ID);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = CompetitionRoomJpaEntity.class)
    @EnableJpaRepositories(basePackageClasses = CompetitionRoomSpringDataRepository.class)
    @Import({CompetitionRoomJpaCommandAdapter.class, CompetitionRoomJooqQueryAdapter.class})
    static class TestApplication {}
}
