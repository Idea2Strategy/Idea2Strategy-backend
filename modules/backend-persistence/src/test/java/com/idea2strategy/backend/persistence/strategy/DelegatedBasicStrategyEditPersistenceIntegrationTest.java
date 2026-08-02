package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.DelegatedBasicEditRejectedException;
import com.idea2strategy.backend.application.strategy.DelegatedBasicEditReplaceResult;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyEditor;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScope;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = DelegatedBasicStrategyEditPersistenceIntegrationTest.TestApplication.class)
@Transactional
class DelegatedBasicStrategyEditPersistenceIntegrationTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000091");
    private static final UUID POLICY_ID = UUID.fromString("20000000-0000-4000-8000-000000000091");
    private static final UUID AUTHORIZATION_ID = UUID.fromString("30000000-0000-4000-8000-000000000091");
    private static final UUID CREDENTIAL_ID = UUID.fromString("40000000-0000-4000-8000-000000000091");
    private static final UUID STRATEGY_ID = UUID.fromString("50000000-0000-4000-8000-000000000091");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

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
    private DelegatedBasicStrategyEditJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareActiveDelegationAndDraft() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                ACCOUNT_ID, at);
        jdbc.update(
                "insert into identity.account_security_states (account_id, auth_epoch, updated_at) values (?, 1, ?)",
                ACCOUNT_ID, at);
        jdbc.update(
                "insert into identity.policy_documents "
                        + "(id, policy_code, version, language_code, title, content_format, content_text, "
                        + "content_hash, published_at) values (?, 'AI_DISCLOSURE', '1', 'ko-KR', 'AI', "
                        + "'TEXT', 'disclosure', ?, ?)",
                POLICY_ID, HASH_A, at);
        jdbc.update(
                "insert into identity.delegated_authorizations "
                        + "(id, account_id, client_label, status, expiry_mode, auth_epoch_at_grant, "
                        + "disclosure_policy_document_id, scope_set_hash, authorized_at) "
                        + "values (?, ?, 'test-cli', 'ACTIVE', 'UNTIL_REVOKED', 1, ?, ?, ?)",
                AUTHORIZATION_ID, ACCOUNT_ID, POLICY_ID, HASH_B, at);
        jdbc.update(
                "insert into identity.delegated_authorization_scopes "
                        + "(authorization_id, scope_code, granted_at) values (?, 'STRATEGY_EDIT', ?)",
                AUTHORIZATION_ID, at);
        jdbc.update(
                "insert into identity.delegated_credentials "
                        + "(id, authorization_id, credential_type, token_digest, digest_key_version, issued_at, expires_at) "
                        + "values (?, ?, 'ACCESS_TOKEN', ?, 1, ?, ?)",
                CREDENTIAL_ID, AUTHORIZATION_ID, HASH_C, at, NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into strategy.strategies "
                        + "(id, owner_account_id, mode, name, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'Delegated draft', 7, ?, ?)",
                STRATEGY_ID, ACCOUNT_ID, at, at);
        jdbc.update(
                "insert into strategy.strategy_documents "
                        + "(strategy_id, semantic_document, presentation_document, semantic_schema_version, "
                        + "presentation_schema_version, semantic_hash, presentation_hash, edit_sequence, "
                        + "created_at, updated_at) values (?, '{}'::jsonb, '{}'::jsonb, 'basic-semantic/v1', "
                        + "'basic-presentation/v1', ?, ?, 7, ?, ?)",
                STRATEGY_ID, HASH_A, HASH_B, at, at);
        jdbc.update(
                "insert into identity.delegated_authorization_strategy_targets "
                        + "(authorization_id, strategy_id, owner_account_id_at_grant, "
                        + "strategy_access_epoch_at_grant, granted_at) values (?, ?, ?, 1, ?)",
                AUTHORIZATION_ID, STRATEGY_ID, ACCOUNT_ID, at);
    }

    @Test
    void atomicallyChecksTheDelegationUpdatesTheDraftAndRecordsAuditEvidence() {
        var editor = new DelegatedStrategyEditor(ACCOUNT_ID, AUTHORIZATION_ID, CREDENTIAL_ID);
        var replacement = new StrategyDocument(
                STRATEGY_ID, "{\"groups\":[]}", "{}", "basic-semantic/v1", "basic-presentation/v1",
                HASH_C, HASH_B, 8, NOW.minusSeconds(60), NOW);

        adapter.requireAuthorized(editor, STRATEGY_ID, DelegatedStrategyScope.STRATEGY_EDIT, NOW);
        assertThat(adapter.replace(replacement, 7, editor, NOW))
                .isEqualTo(DelegatedBasicEditReplaceResult.UPDATED);

        assertThat(jdbc.queryForObject(
                        "select edit_sequence from strategy.strategy_documents where strategy_id = ?",
                        Long.class, STRATEGY_ID))
                .isEqualTo(8L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.audit_events where target_id = ?",
                        Integer.class, STRATEGY_ID))
                .isEqualTo(1);

        jdbc.update(
                "update identity.delegated_credentials set revoked_at = ?, revoke_reason_code = 'USER_REVOKED' "
                        + "where id = ?",
                NOW.plusSeconds(1).atOffset(ZoneOffset.UTC), CREDENTIAL_ID);
        assertThatThrownBy(() -> adapter.requireAuthorized(
                        editor, STRATEGY_ID, DelegatedStrategyScope.STRATEGY_EDIT, NOW.plusSeconds(2)))
                .isInstanceOf(DelegatedBasicEditRejectedException.class);
    }

    @Test
    void rejectsAnOwnedStrategyOutsideTheImmutableTargetSet() {
        UUID outside = UUID.fromString("50000000-0000-4000-8000-000000000092");
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into strategy.strategies "
                        + "(id, owner_account_id, mode, name, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'Outside target', 0, ?, ?)",
                outside, ACCOUNT_ID, at, at);

        var editor = new DelegatedStrategyEditor(ACCOUNT_ID, AUTHORIZATION_ID, CREDENTIAL_ID);
        assertThatThrownBy(() -> adapter.requireAuthorized(
                        editor, outside, DelegatedStrategyScope.STRATEGY_EDIT, NOW))
                .isInstanceOf(DelegatedBasicEditRejectedException.class);
    }

    @Test
    void rejectsAStaleTargetAfterTheStrategyAccessEpochChanges() {
        jdbc.update("update strategy.strategies set delegated_access_epoch = 2 where id = ?", STRATEGY_ID);

        var editor = new DelegatedStrategyEditor(ACCOUNT_ID, AUTHORIZATION_ID, CREDENTIAL_ID);
        assertThatThrownBy(() -> adapter.requireAuthorized(
                        editor, STRATEGY_ID, DelegatedStrategyScope.STRATEGY_EDIT, NOW))
                .isInstanceOf(DelegatedBasicEditRejectedException.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(DelegatedBasicStrategyEditJooqAdapter.class)
    static class TestApplication {}
}
