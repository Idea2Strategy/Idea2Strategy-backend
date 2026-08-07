package com.idea2strategy.backend.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator.RunCommand;
import com.idea2strategy.backend.application.notification.NotificationRequest;
import com.idea2strategy.backend.application.notification.NotificationService;
import com.idea2strategy.backend.persistence.notification.EmailDeliveryGateway;
import com.idea2strategy.backend.persistence.notification.NotificationPersistenceAdapter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A21 acceptance: the production {@link DeadlineBatchConfiguration} wiring drives every
 * deadline category (SANCTION, SESSION, DELEGATED_TOKEN, NOTIFICATION, CASE_DEADLINE)
 * against a real PostgreSQL exactly once, records durable run evidence, and a second run
 * finds no remaining due work. The scheduled runner bean is replaced with a mock so the
 * orchestrator runs deterministically under test control.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = DeadlineBatchPostgresIntegrationTest.TestApplication.class,
        properties = "idea2strategy.batch.deadline.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadlineBatchPostgresIntegrationTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID LOGIN_IDENTITY = id(2);
    private static final UUID SESSION = id(3);
    private static final UUID POLICY = id(4);
    private static final UUID AUTHORIZATION = id(5);
    private static final UUID CREDENTIAL = id(6);
    private static final UUID SANCTION = id(7);
    private static final UUID OPERATOR = id(8);
    private static final UUID CASE_ID = id(9);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired DeadlineBatchOrchestrator orchestrator;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean DeadlineBatchRunner runner;

    @Test
    void everyDeadlineCategoryTransitionsDueWorkExactlyOnceOnRealPostgres() {
        seedIdentityAndSession();
        seedDelegatedCredential();
        seedActiveSuspensionPastExpiry();
        seedCaseNeedingInformationPastDeadline();
        UUID notificationId = seedMandatoryEmailNotification();
        Set<BatchCategory> categories = Set.of(BatchCategory.SANCTION, BatchCategory.SESSION,
                BatchCategory.DELEGATED_TOKEN, BatchCategory.NOTIFICATION, BatchCategory.CASE_DEADLINE);

        var summary = orchestrator.run(new RunCommand(
                UUID.randomUUID(), UUID.randomUUID(), "a21-batch-test", "deadline-batch-v1",
                Duration.ofMinutes(2), 10, categories));

        assertThat(summary.categoryFailures()).as(summary.toString()).isZero();
        assertThat(summary.claimed()).isGreaterThanOrEqualTo(5);
        assertThat(summary.completed()).isGreaterThanOrEqualTo(5);
        assertThat(summary.deadLetters()).isZero();

        assertThat(text("select status::text from identity.account_sanctions where id = ?", SANCTION))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                        "select revoked_at is not null from identity.refresh_token_families where id = ?",
                Boolean.class, SESSION)).isTrue();
        assertThat(count("select count(*) from operations.audit_events "
                + "where idempotency_key like 'delegated-token-expiry:%'")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select response_deadline_at is null from operations.cases where id = ?",
                Boolean.class, CASE_ID)).isTrue();
        assertThat(count("select count(*) from operations.delivery_attempts where notification_id = ?",
                notificationId)).isOne();
        assertThat(text("select delivery_status::text from operations.outbox_messages "
                + "where event_type = 'NOTIFICATION_EMAIL_DELIVERY'"))
                .isNotIn("PENDING", "DEAD_LETTERED");
        assertThat(count("select count(*) from operations.audit_events "
                + "where action_type = 'BATCH_RUN_COMPLETED'")).isGreaterThanOrEqualTo(1);

        var second = orchestrator.run(new RunCommand(
                UUID.randomUUID(), UUID.randomUUID(), "a21-batch-test", "deadline-batch-v1",
                Duration.ofMinutes(2), 10, categories));
        assertThat(second.claimed()).isZero();
        assertThat(second.categoryFailures()).isZero();
    }

    private void seedIdentityAndSession() {
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofDays(2)));
        Timestamp expired = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", ACCOUNT);
        jdbc.update("insert into identity.auth_providers (id, code, display_name, provider_type) "
                + "values (21101, 'A21B_TEST', 'A21 batch test', 'PASSWORD')");
        jdbc.update("insert into identity.login_identities "
                        + "(id, account_id, provider_id, status, activated_at) values (?, ?, 21101, 'ACTIVE', ?)",
                LOGIN_IDENTITY, ACCOUNT, past);
        jdbc.update("insert into identity.refresh_token_families "
                        + "(id, account_id, authenticated_by_login_identity_id, auth_epoch_at_issue, "
                        + "current_token_digest, digest_key_version, issued_at, last_rotated_at, expires_at) "
                        + "values (?, ?, ?, 1, ?, 1, ?, ?, ?)",
                SESSION, ACCOUNT, LOGIN_IDENTITY, "a21b-session-" + SESSION, past, past, expired);
    }

    private void seedDelegatedCredential() {
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofDays(2)));
        Timestamp expired = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        Timestamp future = Timestamp.from(Instant.now().plus(Duration.ofDays(7)));
        jdbc.update("insert into identity.policy_documents "
                        + "(id, policy_code, version, language_code, title, content_format, content_text, "
                        + "content_hash, published_at) "
                        + "values (?, 'A21B_DISCLOSURE', '1', 'ko-KR', 'A21B', 'MARKDOWN', 'batch', ?, ?)",
                POLICY, "d".repeat(64), past);
        jdbc.update("insert into identity.delegated_authorizations "
                        + "(id, account_id, client_label, status, expiry_mode, auth_epoch_at_grant, "
                        + "disclosure_policy_document_id, scope_set_hash, authorized_at, expires_at) "
                        + "values (?, ?, 'a21b-cli', 'ACTIVE', 'AT_TIME', 1, ?, ?, ?, ?)",
                AUTHORIZATION, ACCOUNT, POLICY, "e".repeat(64), past, future);
        jdbc.update("insert into identity.delegated_credentials "
                        + "(id, authorization_id, credential_type, token_digest, digest_key_version, "
                        + "issued_at, expires_at) values (?, ?, 'ACCESS_TOKEN', ?, 1, ?, ?)",
                CREDENTIAL, AUTHORIZATION, "a21b-credential-" + CREDENTIAL, past, expired);
    }

    private void seedActiveSuspensionPastExpiry() {
        Timestamp applied = Timestamp.from(Instant.now().minus(Duration.ofDays(1)));
        Timestamp expired = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, external_identity_key_version, status, "
                        + "mfa_enrolled_at, created_at) values (?, ?, 1, 'ACTIVE', ?, ?)",
                OPERATOR, "f".repeat(64), now, now);
        jdbc.update("insert into identity.account_sanctions "
                        + "(id, account_id, sanction_type, status, reason_code, applied_by_operator_id, "
                        + "applied_at, effective_at, expires_at, status_changed_at) "
                        + "values (?, ?, 'SUSPENSION', 'ACTIVE', 'A21B_TEST', ?, ?, ?, ?, ?)",
                SANCTION, ACCOUNT, OPERATOR, applied, applied, expired, applied);
        jdbc.update("insert into identity.account_sanction_heads (account_id, aggregate_version) "
                + "values (?, 1)", ACCOUNT);
    }

    private void seedCaseNeedingInformationPastDeadline() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("set constraints all deferred");
            UUID submitted = id(20);
            UUID requested = id(21);
            Timestamp created = Timestamp.from(Instant.now().minus(Duration.ofDays(10)));
            Timestamp deadline = Timestamp.from(Instant.now().minus(Duration.ofHours(2)));
            jdbc.update("""
                    insert into operations.cases
                        (id, account_id, case_type, status, subject, case_version,
                         current_event_sequence, last_case_event_id, response_deadline_at,
                         deadline_policy_version, created_at, updated_at)
                    values (?, ?, 'REPORT', 'NEEDS_INFORMATION', 'a21b-deadline', 2, 2, ?, ?,
                            'case-response-v1', ?, ?)
                    """, CASE_ID, ACCOUNT, requested, deadline, created, created);
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
        });
    }

    private UUID seedMandatoryEmailNotification() {
        jdbc.update("""
                insert into operations.notification_policies
                    (type_code, policy_version, mandatory, default_channels, active, activated_at)
                values ('ACCOUNT_SECURITY', 'a21b-v1', true, cast('["APP","EMAIL"]' as jsonb),
                        true, clock_timestamp())
                """);
        NotificationPersistenceAdapter adapter = new NotificationPersistenceAdapter(jdbc, json);
        NotificationService service = new NotificationService(adapter, adapter, adapter, Clock.systemUTC());
        return service.create(new NotificationRequest(ACCOUNT, "ACCOUNT_SECURITY", "template-v1", "ko",
                "a21b-source-event", "a".repeat(64), Map.of("subject", "a21b"), UUID.randomUUID()))
                .notificationId();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a21b0000-0000-4000-8000-" + "%012d".formatted(suffix));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({FakeGatewayConfiguration.class, DeadlineBatchConfiguration.class})
    static class TestApplication {}

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class FakeGatewayConfiguration {
        @Bean
        EmailDeliveryGateway fakeEmailDeliveryGateway() {
            return message -> EmailDeliveryGateway.DeliveryResult.sent("a21b-provider-key");
        }
    }
}
