package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.delegation.DelegatedCredentialExpiryPort;
import com.idea2strategy.backend.application.identity.SessionExpiryPort;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = IdentityExpiryJdbcAdapterIntegrationTest.TestApplication.class)
class IdentityExpiryJdbcAdapterIntegrationTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID PROVIDER = id(2);
    private static final UUID SESSION = id(3);
    private static final UUID POLICY = id(4);
    private static final UUID AUTHORIZATION = id(5);
    private static final UUID CREDENTIAL = id(6);
    private static final UUID NEW_CREDENTIAL = id(7);
    private static final Instant EXPIRES_AT = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant AUTHORIZATION_EXPIRES_AT = Instant.parse("2021-01-01T00:00:00Z");
    private static final Instant NEW_CREDENTIAL_EXPIRES_AT = Instant.parse("2099-01-01T00:00:00Z");
    private static final String HASH = "a".repeat(64);

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

    @Autowired IdentityExpiryJdbcAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from identity.authentication_events where account_id = ?", ACCOUNT);
        jdbc.update("delete from identity.refresh_token_families where account_id = ?", ACCOUNT);
        jdbc.update("delete from identity.login_identities where account_id = ?", ACCOUNT);
        jdbc.update("delete from identity.auth_providers where id = 21001");
        jdbc.update("delete from identity.delegated_authorization_events where authorization_id = ?", AUTHORIZATION);
        jdbc.update("delete from operations.audit_events where target_id in (?, ?)", CREDENTIAL, NEW_CREDENTIAL);
        jdbc.update("delete from identity.delegated_credentials where authorization_id = ?", AUTHORIZATION);
        jdbc.update("delete from identity.delegated_authorizations where id = ?", AUTHORIZATION);
        jdbc.update("delete from identity.policy_documents where id = ?", POLICY);
        var at = EXPIRES_AT.minusSeconds(3600).atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE') "
                + "on conflict (id) do update set lifecycle_status = 'ACTIVE'", ACCOUNT);
        jdbc.update("insert into identity.auth_providers (id, code, display_name, provider_type) "
                + "values (21001, 'A21_TEST', 'A21 test', 'PASSWORD')");
        jdbc.update("insert into identity.login_identities "
                + "(id, account_id, provider_id, status, activated_at) values (?, ?, 21001, 'ACTIVE', ?)",
                PROVIDER, ACCOUNT, at);
        jdbc.update("insert into identity.refresh_token_families "
                + "(id, account_id, authenticated_by_login_identity_id, auth_epoch_at_issue, current_token_digest, "
                + "digest_key_version, issued_at, last_rotated_at, expires_at) "
                + "values (?, ?, ?, 1, ?, 1, ?, ?, ?)",
                SESSION, ACCOUNT, PROVIDER, "session-" + SESSION, at, at, EXPIRES_AT.atOffset(ZoneOffset.UTC));

        jdbc.update("insert into identity.policy_documents "
                + "(id, policy_code, version, language_code, title, content_format, content_text, "
                + "content_hash, published_at) values (?, 'A21_DISCLOSURE', '1', 'ko-KR', 'A21', "
                + "'TEXT', 'A21', ?, ?)", POLICY, HASH, at);
        jdbc.update("insert into identity.delegated_authorizations "
                + "(id, account_id, client_label, status, expiry_mode, auth_epoch_at_grant, "
                + "disclosure_policy_document_id, scope_set_hash, authorized_at, expires_at) "
                + "values (?, ?, 'a21-cli', 'ACTIVE', 'AT_TIME', 1, ?, ?, ?, ?)",
                AUTHORIZATION, ACCOUNT, POLICY, HASH, at, AUTHORIZATION_EXPIRES_AT.atOffset(ZoneOffset.UTC));
        jdbc.update("insert into identity.delegated_credentials "
                + "(id, authorization_id, credential_type, token_digest, digest_key_version, issued_at, expires_at) "
                + "values (?, ?, 'ACCESS_TOKEN', ?, 1, ?, ?)",
                CREDENTIAL, AUTHORIZATION, "delegated-" + CREDENTIAL, at,
                EXPIRES_AT.atOffset(ZoneOffset.UTC));
        jdbc.update("insert into identity.delegated_credentials "
                + "(id, authorization_id, credential_type, token_digest, digest_key_version, issued_at, expires_at) "
                + "values (?, ?, 'ACCESS_TOKEN', ?, 1, ?, ?)",
                NEW_CREDENTIAL, AUTHORIZATION, "delegated-" + NEW_CREDENTIAL, at,
                NEW_CREDENTIAL_EXPIRES_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void concurrentSessionExpiryHasOneTransitionAndOneAuditEvent() throws Exception {
        SessionExpiryPort.Identity due = adapter.findDueSessions(10).getFirst();
        List<SessionExpiryPort.Result> results = concurrently(() ->
                adapter.expire(due, UUID.randomUUID()));

        assertThat(results).containsExactlyInAnyOrder(
                SessionExpiryPort.Result.APPLIED, SessionExpiryPort.Result.ALREADY_TRANSITIONED);
        assertThat(count("select count(*) from identity.authentication_events "
                + "where account_id = ? and event_type = 'SESSION_EXPIRED'", ACCOUNT)).isOne();
        assertThat(text("select revoke_reason_code from identity.refresh_token_families where id = ?", SESSION))
                .isEqualTo("SESSION_EXPIRED");
        assertThat(adapter.findDueSessions(10)).isEmpty();
    }

    @Test
    void normalAuthenticationAndExpirySerializeTheSameAccountEventSequence() throws Exception {
        SessionExpiryPort.Identity due = adapter.findDueSessions(10).getFirst();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(
                    () -> adapter.expire(due, UUID.randomUUID()).name(),
                    () -> {
                        recordNormalAuthenticationEvent();
                        return "AUTH_RECORDED";
                    }));
            assertThat(futures).allSatisfy(future -> {
                try { assertThat(future.get()).isNotNull(); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            });
        }
        assertThat(jdbc.queryForList("select event_sequence from identity.authentication_events "
                + "where account_id = ? order by event_sequence", Long.class, ACCOUNT))
                .containsExactly(1L, 2L);
    }

    @Test
    void rotatedOldCredentialExpiryDoesNotExpireAuthorizationOrNewCredential() throws Exception {
        DelegatedCredentialExpiryPort.Identity due = adapter.findDueCredentials(10).stream()
                .filter(candidate -> candidate.kind() == DelegatedCredentialExpiryPort.Kind.CREDENTIAL)
                .findFirst().orElseThrow();
        List<DelegatedCredentialExpiryPort.Result> results = concurrently(() ->
                adapter.expire(due, UUID.randomUUID()));

        assertThat(results).containsExactlyInAnyOrder(
                DelegatedCredentialExpiryPort.Result.APPLIED,
                DelegatedCredentialExpiryPort.Result.ALREADY_TRANSITIONED);
        assertThat(count("select count(*) from operations.audit_events "
                + "where target_id = ? and action_type = 'DELEGATED_CREDENTIAL_EXPIRED'", CREDENTIAL)).isOne();
        assertThat(text("select status::text from identity.delegated_authorizations where id = ?", AUTHORIZATION))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select revoked_at is null and expires_at > clock_timestamp() "
                + "from identity.delegated_credentials where id = ?", Boolean.class, NEW_CREDENTIAL)).isTrue();
        assertThat(adapter.findDueCredentials(10)).noneMatch(candidate ->
                candidate.kind() == DelegatedCredentialExpiryPort.Kind.CREDENTIAL);
    }

    @Test
    void authorizationTransitionsOnlyAtItsDistinctAuthorizationDeadline() throws Exception {
        DelegatedCredentialExpiryPort.Identity due = adapter.findDueCredentials(10).stream()
                .filter(candidate -> candidate.kind() == DelegatedCredentialExpiryPort.Kind.AUTHORIZATION)
                .findFirst().orElseThrow();
        List<DelegatedCredentialExpiryPort.Result> results = concurrently(() ->
                adapter.expire(due, UUID.randomUUID()));

        assertThat(results).containsExactlyInAnyOrder(
                DelegatedCredentialExpiryPort.Result.APPLIED,
                DelegatedCredentialExpiryPort.Result.ALREADY_TRANSITIONED);
        assertThat(text("select status::text from identity.delegated_authorizations where id = ?", AUTHORIZATION))
                .isEqualTo("EXPIRED");
        assertThat(count("select count(*) from identity.delegated_authorization_events "
                + "where authorization_id = ? and event_type = 'EXPIRED'", AUTHORIZATION)).isOne();
    }

    private <T> List<T> concurrently(Callable<T> operation) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            return executor.invokeAll(List.of(operation, operation)).stream()
                    .map(future -> {
                        try { return future.get(); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                    }).toList();
        }
    }

    private void recordNormalAuthenticationEvent() {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.queryForObject("select id from identity.accounts where id = ? for update", UUID.class, ACCOUNT);
            jdbc.update("""
                    insert into identity.authentication_events
                        (id, account_id, event_sequence, event_type, subject_login_identity_id,
                         actor_type, correlation_id, idempotency_key, occurred_at)
                    values (?, ?,
                        (select coalesce(max(event_sequence), 0) + 1
                           from identity.authentication_events where account_id = ?),
                        'LOGIN_SUCCEEDED', ?, 'USER', ?, ?, clock_timestamp())
                    """, UUID.randomUUID(), ACCOUNT, ACCOUNT, PROVIDER, UUID.randomUUID(),
                    "normal-auth:" + UUID.randomUUID());
        });
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2110000-0000-4000-8000-%012d".formatted(suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(IdentityExpiryJdbcAdapter.class)
    static class TestApplication {}
}
