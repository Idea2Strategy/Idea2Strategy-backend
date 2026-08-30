package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.strategy.StrategyDraftConflictException;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseInvalidException;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseTokens;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestCustomerAccessPrincipal;
import com.idea2strategy.backend.domain.strategy.StrategyEditLease;
import com.idea2strategy.backend.domain.strategy.StrategyCreated;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BasicStrategyDraftPersistenceIntegrationTest.TestApplication.class)
class BasicStrategyDraftPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID LOGIN_ID = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_SESSION_ID = UUID.fromString("12000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T03:00:00Z");
    private static final String LEASE_TOKEN = "integration-lease-token";

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
    private BasicStrategyDraftJpaCommandAdapter draftCommandAdapter;

    @Autowired
    private StrategyEditLeaseJpaCommandAdapter leaseCommandAdapter;

    @Autowired
    private StrategyJooqQueryAdapter strategyQueryAdapter;

    @Autowired
    private StrategyDocumentJooqQueryAdapter documentQueryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareOwner() {
        jdbcTemplate.update("delete from strategy.strategy_edit_leases");
        jdbcTemplate.update("delete from strategy.strategy_documents");
        jdbcTemplate.update("delete from strategy.strategies");
        jdbcTemplate.update("delete from identity.login_identities where id = ?", LOGIN_ID);
        jdbcTemplate.execute(
                "truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update(
                """
                insert into identity.auth_providers (id, code, display_name, provider_type)
                values (99, 'TEST_PASSWORD', 'Test Password', 'PASSWORD')
                on conflict (id) do nothing
                """);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                """
                insert into identity.login_identities
                    (id, account_id, provider_id, status, activated_at)
                values (?, ?, 99, 'ACTIVE', ?)
                """,
                LOGIN_ID,
                OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void createsDraftRowsAndRejectsAStaleConditionalUpdate() {
        var principal = new TestCustomerAccessPrincipal(OWNER_ID);
        var events = new RecordingDomainEventPublisher();
        var service = new BasicStrategyDraftCommandService(
                draftCommandAdapter,
                strategyQueryAdapter,
                documentQueryAdapter,
                principal,
                new FixedIdGenerator(STRATEGY_ID),
                Clock.fixed(NOW, ZoneOffset.UTC),
                events);

        UUID strategyId = service.createBasic("Momentum", null);
        var latest = service.autosave(
                strategyId,
                0,
                null,
                "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"latest\"}]}",
                "{\"positions\":{},\"viewport\":{\"x\":10,\"y\":20,\"zoom\":1}}",
                "basic-semantic/v1",
                "basic-presentation/v1");

        assertThatThrownBy(() -> service.saveExplicitly(
                        strategyId,
                        0,
                        null,
                        "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"stale\"}]}",
                        "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
                        "basic-semantic/v1",
                        "basic-presentation/v1"))
                .isInstanceOf(StrategyDraftConflictException.class);

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from strategy.strategies where id = ?", Integer.class, STRATEGY_ID))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from strategy.strategy_documents where strategy_id = ?",
                        Integer.class,
                        STRATEGY_ID))
                .isEqualTo(1);
        assertThat(new StrategyDocumentQueryService(documentQueryAdapter, principal).getOwned(strategyId))
                .isEqualTo(latest);
        assertThat(draftCommandAdapter.replaceDocument(
                        latest, 0, OWNER_ID, "", NOW))
                .isEqualTo(com.idea2strategy.backend.application.strategy.StrategyDraftReplaceResult.STALE_EDIT_SEQUENCE);
        assertThat(service.autosave(
                        strategyId,
                        1,
                        null,
                        "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"next\"}]}",
                        "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
                        "basic-semantic/v1",
                        "basic-presentation/v1").editSequence())
                .isEqualTo(2);
        assertThat(events.publishedEvents()).containsExactly(
                new StrategyCreated(STRATEGY_ID, OWNER_ID, StrategyMode.BASIC, NOW));
    }

    @Test
    void leasePersistenceBlocksCompetingSessionsAndAllowsRecoveryAfterRelease() {
        var principal = new TestCustomerAccessPrincipal(OWNER_ID);
        var service = new BasicStrategyDraftCommandService(
                draftCommandAdapter,
                strategyQueryAdapter,
                documentQueryAdapter,
                principal,
                new FixedIdGenerator(STRATEGY_ID),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new RecordingDomainEventPublisher());
        UUID strategyId = service.createBasic("Lease test", null);
        String firstDigest = StrategyEditLeaseTokens.sha256("first-token");
        String secondDigest = StrategyEditLeaseTokens.sha256("second-token");

        assertThat(leaseCommandAdapter.acquire(
                        lease(strategyId, OWNER_ID, firstDigest, NOW, NOW.plusSeconds(60)), NOW))
                .isTrue();
        assertThat(leaseCommandAdapter.acquire(
                        lease(strategyId, OWNER_ID, secondDigest, NOW.plusSeconds(10), NOW.plusSeconds(70)),
                        NOW.plusSeconds(10)))
                .isFalse();
        assertThat(leaseCommandAdapter.heartbeat(
                        strategyId,
                        OWNER_ID,
                        firstDigest,
                        NOW.plusSeconds(20),
                        NOW.plusSeconds(80)))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "select lease_token_digest from strategy.strategy_edit_leases where strategy_id = ?",
                        String.class,
                        strategyId))
                .isEqualTo(firstDigest)
                .doesNotContain("first-token");
        assertThat(leaseCommandAdapter.release(strategyId, OWNER_ID, firstDigest)).isTrue();
        assertThat(leaseCommandAdapter.acquire(
                        lease(strategyId, OWNER_ID, secondDigest, NOW.plusSeconds(21), NOW.plusSeconds(81)),
                        NOW.plusSeconds(21)))
                .isTrue();
    }

    private static StrategyEditLease lease(
            UUID strategyId, UUID accountId, String digest, Instant acquiredAt, Instant expiresAt) {
        return new StrategyEditLease(
                strategyId,
                accountId,
                digest,
                StrategyEditLeaseTokens.DIGEST_KEY_VERSION,
                acquiredAt,
                acquiredAt,
                expiresAt);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {StrategyJpaEntity.class, StrategyDocumentJpaEntity.class})
    @EnableJpaRepositories(basePackageClasses = {
        StrategySpringDataRepository.class,
        StrategyDocumentSpringDataRepository.class
    })
    @Import({
        BasicStrategyDraftJpaCommandAdapter.class,
        StrategyEditLeaseJpaCommandAdapter.class,
        StrategyJooqQueryAdapter.class,
        StrategyDocumentJooqQueryAdapter.class
    })
    static class TestApplication {}
}
