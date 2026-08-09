package com.idea2strategy.backend.persistence.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommand;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommandType;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationScope;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationService;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationStatus;
import com.idea2strategy.backend.application.delegation.DelegatedCredentialMaterial;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyEditor;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScope;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScopeDeniedException;
import com.idea2strategy.backend.persistence.strategy.DelegatedBasicStrategyEditJooqAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Grants a delegation with the production adapter, then edits under it with the production
 * authorization check.
 *
 * <p>This is the join the project had no coverage for. The edit check reads ten columns across five
 * tables — status, expiry mode and instant, auth epoch, credential type and expiry, pinned owner and
 * access epoch — and any one of them written differently by the grant makes every edit deny with no
 * indication of which column was wrong. Asserting the grant's rows in isolation would not catch
 * that; only running the real check against the real grant does.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = DelegatedAuthorizationGrantPersistenceIntegrationTest.TestApplication.class)
class DelegatedAuthorizationGrantPersistenceIntegrationTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000092");
    private static final UUID STRATEGY_ID = UUID.fromString("50000000-0000-4000-8000-000000000092");
    private static final UUID OTHER_STRATEGY_ID = UUID.fromString("50000000-0000-4000-8000-000000000093");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

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
    private DelegatedAuthorizationJooqAdapter grantAdapter;

    @Autowired
    private DelegatedBasicStrategyEditJooqAdapter editAdapter;

    @Autowired
    private JdbcTemplate jdbc;

    private DelegatedAuthorizationService service;

    /**
     * Resolved from the database rather than inserted here: the migration already publishes this
     * document, and a fixture that inserted its own would silently stop covering the row a real
     * grant actually points at.
     */
    private UUID policyId;

    @BeforeEach
    void prepareAccountAndStrategies() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("delete from identity.delegated_authorization_events");
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?) "
                        + "on conflict (id) do nothing",
                ACCOUNT_ID, at);
        jdbc.update(
                "insert into identity.account_security_states (account_id, auth_epoch, updated_at) "
                        + "values (?, 4, ?) on conflict (account_id) do nothing",
                ACCOUNT_ID, at);
        policyId = grantAdapter.currentDisclosurePolicyDocumentId("delegation.strategy-edit.disclosure");
        insertStrategy(STRATEGY_ID, "Delegated draft");
        insertStrategy(OTHER_STRATEGY_ID, "Untargeted draft");

        service = new DelegatedAuthorizationService(
                grantAdapter,
                // token_digest is UNIQUE, as it must be: two delegations sharing a digest would
                // authorize each other. The real HMAC of a random 256-bit value satisfies that, so
                // the fake has to vary too or it tests a constraint violation instead of a grant.
                () -> new DelegatedCredentialMaterial(
                        "raw-" + UUID.randomUUID(),
                        UUID.randomUUID().toString().replace("-", "").repeat(2),
                        (short) 1),
                Clock.fixed(NOW, ZoneOffset.UTC),
                UUID::randomUUID);
    }

    @Test
    void grantsADelegationTheEditCheckAccepts() {
        UUID authorizationId = UUID.randomUUID();

        var result = service.execute(createCommand(authorizationId, "grant-1", Set.of(STRATEGY_ID)));

        assertThat(result.status()).isEqualTo(DelegatedAuthorizationStatus.ACTIVE);
        assertThat(result.rawCredential()).isPresent();
        editAdapter.requireAuthorized(
                new DelegatedStrategyEditor(ACCOUNT_ID, authorizationId, result.credentialId()),
                STRATEGY_ID,
                DelegatedStrategyScope.STRATEGY_EDIT,
                NOW.plusSeconds(60));
    }

    @Test
    void deniesAStrategyTheGrantNeverTargeted() {
        UUID authorizationId = UUID.randomUUID();
        var result = service.execute(createCommand(authorizationId, "grant-2", Set.of(STRATEGY_ID)));

        assertThatThrownBy(() -> editAdapter.requireAuthorized(
                        new DelegatedStrategyEditor(ACCOUNT_ID, authorizationId, result.credentialId()),
                        OTHER_STRATEGY_ID,
                        DelegatedStrategyScope.STRATEGY_EDIT,
                        NOW.plusSeconds(60)))
                .isInstanceOf(DelegatedStrategyScopeDeniedException.class);
    }

    /** The disclosure promises an expiry, so a grant that carries one must actually stop working. */
    @Test
    void deniesTheDelegationOnceItsExpiryHasPassed() {
        UUID authorizationId = UUID.randomUUID();
        var result = service.execute(createCommand(authorizationId, "grant-3", Set.of(STRATEGY_ID)));

        assertThatThrownBy(() -> editAdapter.requireAuthorized(
                        new DelegatedStrategyEditor(ACCOUNT_ID, authorizationId, result.credentialId()),
                        STRATEGY_ID,
                        DelegatedStrategyScope.STRATEGY_EDIT,
                        NOW.plusSeconds(86_400 + 60)))
                .isInstanceOf(DelegatedStrategyScopeDeniedException.class);
    }

    @Test
    void replayingTheSameCommandReturnsTheFirstGrantWithoutIssuingASecondCredential() {
        UUID authorizationId = UUID.randomUUID();
        var command = createCommand(authorizationId, "grant-4", Set.of(STRATEGY_ID));

        var first = service.execute(command);
        var replay = service.execute(command);

        assertThat(replay.authorizationId()).isEqualTo(first.authorizationId());
        assertThat(replay.credentialId()).isEqualTo(first.credentialId());
        assertThat(replay.rawCredential()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.delegated_credentials where authorization_id = ?",
                        Integer.class,
                        authorizationId))
                .isEqualTo(1);
    }

    @Test
    void revokedDelegationsStopAuthorizingImmediately() {
        UUID authorizationId = UUID.randomUUID();
        var result = service.execute(createCommand(authorizationId, "grant-5", Set.of(STRATEGY_ID)));

        service.execute(new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.REVOKE, ACCOUNT_ID, authorizationId, null, 1L, 4L,
                "revoked", policyId, Set.of(), Set.of(), null, "USER_REQUESTED", "revoke-5",
                "revoke-hash-5", UUID.randomUUID()));

        assertThatThrownBy(() -> editAdapter.requireAuthorized(
                        new DelegatedStrategyEditor(ACCOUNT_ID, authorizationId, result.credentialId()),
                        STRATEGY_ID,
                        DelegatedStrategyScope.STRATEGY_EDIT,
                        NOW.plusSeconds(60)))
                .isInstanceOf(DelegatedStrategyScopeDeniedException.class);
    }

    @Test
    void refusesToTargetAStrategyTheAccountDoesNotOwn() {
        UUID foreignStrategy = UUID.randomUUID();

        assertThatThrownBy(() -> service.execute(
                        createCommand(UUID.randomUUID(), "grant-6", Set.of(foreignStrategy))))
                .isInstanceOf(DelegatedStrategyTargetRejectedException.class);
    }

    private DelegatedAuthorizationCommand createCommand(
            UUID authorizationId, String idempotencyKey, Set<UUID> targets) {
        return new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.CREATE,
                ACCOUNT_ID,
                authorizationId,
                null,
                0L,
                4L,
                "external-assistant",
                policyId,
                Set.of(DelegatedAuthorizationScope.STRATEGY_EDIT),
                targets,
                NOW.plusSeconds(86_400),
                "USER_REQUESTED",
                idempotencyKey,
                "request-hash-" + idempotencyKey,
                UUID.randomUUID());
    }

    private void insertStrategy(UUID strategyId, String name) {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into strategy.strategies "
                        + "(id, owner_account_id, mode, name, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 1, ?, ?) on conflict (id) do nothing",
                strategyId, ACCOUNT_ID, name, at, at);
        jdbc.update(
                "insert into strategy.strategy_documents "
                        + "(strategy_id, semantic_document, presentation_document, semantic_schema_version, "
                        + "presentation_schema_version, semantic_hash, presentation_hash, edit_sequence, "
                        + "created_at, updated_at) values (?, '{}'::jsonb, '{}'::jsonb, 'basic-semantic/v1', "
                        + "'basic-presentation/v1', ?, ?, 1, ?, ?) on conflict (strategy_id) do nothing",
                strategyId, HASH_A, HASH_B, at, at);
    }

    @SpringBootApplication
    @Import({DelegatedAuthorizationJooqAdapter.class, DelegatedBasicStrategyEditJooqAdapter.class})
    static class TestApplication {}
}
