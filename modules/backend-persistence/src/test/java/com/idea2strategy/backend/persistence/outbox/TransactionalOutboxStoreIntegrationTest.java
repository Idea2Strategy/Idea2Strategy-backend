package com.idea2strategy.backend.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.OutboxConflictException;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ReceiptDisposition;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TransactionalOutboxStoreIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionalOutboxStoreIntegrationTest {
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

    @Autowired TransactionalOutboxStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from operations.outbox_consumer_receipts");
        jdbc.update("delete from operations.outbox_delivery_attempts");
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from operations.audit_events where action_type = 'OPERATIONS_OUTBOX_REPLAY'");
        jdbc.update("delete from operations.operator_role_assignments");
        jdbc.update("delete from operations.rbac_catalog_role_permissions");
        jdbc.update("delete from operations.rbac_catalog_permissions");
        jdbc.update("delete from operations.rbac_catalog_roles");
        jdbc.update("delete from operations.rbac_catalog_versions");
        jdbc.update("delete from operations.role_permissions");
        jdbc.update("delete from operations.permissions where code = 'OPERATIONS_OUTBOX_REPLAY'");
        jdbc.update("delete from operations.roles where code = 'OUTBOX_OPERATOR'");
        jdbc.update("delete from operations.operator_accounts");
    }

    @Test
    void twoWorkersCannotOwnOneMessageAndStaleAckIsRejectedAfterLeaseRecovery() throws Exception {
        UUID messageId = insertMessage("claim-once");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.claimDue("worker-a", "policy-v1", Duration.ofMillis(80), 1));
            var second = executor.submit(() -> store.claimDue("worker-b", "policy-v1", Duration.ofMillis(80), 1));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.size() + b.size()).isEqualTo(1);
            var original = a.isEmpty() ? b.getFirst() : a.getFirst();

            Thread.sleep(120);
            var recovered = store.claimDue("worker-c", "policy-v2", Duration.ofSeconds(5), 1).getFirst();
            assertThat(recovered.messageId()).isEqualTo(messageId);
            assertThat(recovered.attemptNumber()).isEqualTo(2);
            assertThatThrownBy(() -> store.acknowledge(messageId, original.claimToken(), "late"))
                    .isInstanceOf(OutboxConflictException.class);
            store.acknowledge(messageId, recovered.claimToken(), "transport-2");
        }

        assertThat(text("select delivery_status::text from operations.outbox_messages where id = ?", messageId))
                .isEqualTo("PUBLISHED");
        assertThat(count("select count(*) from operations.outbox_delivery_attempts where outbox_message_id = ? and outcome = 'LEASE_EXPIRED'", messageId))
                .isEqualTo(1);
        assertThat(count("select count(*) from operations.outbox_delivery_attempts where outbox_message_id = ? and outcome = 'PUBLISHED'", messageId))
                .isEqualTo(1);
    }

    @Test
    void retryAndDeadLetterAreDistinctAndAuthorizedReplayPreservesEnvelope() {
        UUID messageId = insertMessage("retry-dead-letter");
        var first = store.claimDue("worker-a", "policy-v1", Duration.ofSeconds(5), 1).getFirst();
        store.retry(messageId, first.claimToken(), "BROKER_UNAVAILABLE", Instant.now().minusSeconds(1));
        assertThat(text("select delivery_status::text from operations.outbox_messages where id = ?", messageId))
                .isEqualTo("PENDING");

        var second = store.claimDue("worker-b", "policy-v1", Duration.ofSeconds(5), 1).getFirst();
        store.deadLetter(messageId, second.claimToken(), "UNSUPPORTED_SCHEMA");
        assertThat(text("select delivery_status::text from operations.outbox_messages where id = ?", messageId))
                .isEqualTo("DEAD_LETTERED");

        UUID operatorId = authorizedOperator();
        UUID correlation = UUID.randomUUID();
        UUID replay = store.replay(messageId, operatorId, "APPROVED_REPROCESS", correlation, "replay-command-1");
        UUID sameReplay = store.replay(messageId, operatorId, "APPROVED_REPROCESS", correlation, "replay-command-1");
        assertThat(sameReplay).isEqualTo(replay);
        assertThat(text("select delivery_status::text from operations.outbox_messages where id = ?", replay))
                .isEqualTo("PENDING");
        assertThat(text("select payload_hash from operations.outbox_messages where id = ?", replay))
                .isEqualTo(text("select payload_hash from operations.outbox_messages where id = ?", messageId));
        assertThat(text("select producer_idempotency_key from operations.outbox_messages where id = ?", replay))
                .isEqualTo("retry-dead-letter");
        assertThat(jdbc.queryForObject("select replayed_from_message_id from operations.outbox_messages where id = ?", UUID.class, replay))
                .isEqualTo(messageId);
        assertThat(count("select count(*) from operations.audit_events where target_id = ? and action_type = 'OPERATIONS_OUTBOX_REPLAY'", replay))
                .isEqualTo(1);
        assertThatThrownBy(() -> store.replay(messageId, operatorId, "CHANGED", correlation, "replay-command-1"))
                .isInstanceOf(OutboxConflictException.class);
    }

    @Test
    void consumerReceiptCompletesEffectOnceAndRejectsPayloadConflict() {
        UUID messageId = insertMessage("consumer-once");
        String hash = text("select payload_hash from operations.outbox_messages where id = ?", messageId);
        var claim = store.receive("handler-v1", messageId, "consumer-once", hash,
                "consumer-a", Duration.ofSeconds(5));
        assertThat(claim.disposition()).isEqualTo(ReceiptDisposition.ACQUIRED);
        store.completeReceipt("handler-v1", messageId, claim.claimToken(), "result-hash");

        var duplicate = store.receive("handler-v1", messageId, "consumer-once", hash,
                "consumer-b", Duration.ofSeconds(5));
        assertThat(duplicate.disposition()).isEqualTo(ReceiptDisposition.COMPLETED);
        assertThat(duplicate.resultHash()).isEqualTo("result-hash");
        assertThatThrownBy(() -> store.receive("handler-v1", messageId, "consumer-once",
                "different-hash", "consumer-c", Duration.ofSeconds(5)))
                .isInstanceOf(OutboxConflictException.class);
        assertThat(count("select receive_attempt_count from operations.outbox_consumer_receipts where consumer_handler_id = 'handler-v1' and outbox_message_id = ?", messageId))
                .isEqualTo(2);
    }

    private UUID insertMessage(String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, event_type, event_schema_version,
                     payload_document, idempotency_key)
                values (?, 'test', ?, 'TEST_EVENT', '1.0.0', '{"value":1}'::jsonb, ?)
                """, id, UUID.randomUUID(), key);
        return id;
    }

    private UUID authorizedOperator() {
        UUID operator = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID permission = UUID.randomUUID();
        String catalogVersion = "outbox-test-" + UUID.randomUUID();
        jdbc.update("""
                insert into operations.operator_accounts (id, status, created_at)
                values (?, 'ACTIVE', clock_timestamp())
                """, operator);
        jdbc.update("insert into operations.roles (id, code, hierarchy_rank, status) values (?, 'OUTBOX_OPERATOR', 1, 'ACTIVE')", role);
        jdbc.update("insert into operations.permissions (id, code, description, sensitivity) values (?, 'OPERATIONS_OUTBOX_REPLAY', 'Replay dead letters', 'HIGH')", permission);
        jdbc.update("insert into operations.role_permissions (role_id, permission_id) values (?, ?)", role, permission);
        jdbc.update("insert into operations.rbac_catalog_versions (catalog_version, content_hash, status) values (?, ?, 'DRAFT')",
                catalogVersion, "a".repeat(64));
        jdbc.update("insert into operations.rbac_catalog_roles (catalog_version, role_id, hierarchy_rank, role_status) values (?, ?, 1, 'ACTIVE')",
                catalogVersion, role);
        jdbc.update("insert into operations.rbac_catalog_permissions (catalog_version, permission_id, permission_status) values (?, ?, 'ACTIVE')",
                catalogVersion, permission);
        jdbc.update("insert into operations.rbac_catalog_role_permissions (catalog_version, role_id, permission_id, delegable) values (?, ?, ?, false)",
                catalogVersion, role, permission);
        jdbc.update("update operations.rbac_catalog_versions set status = 'ACTIVE', activated_at = clock_timestamp() where catalog_version = ?",
                catalogVersion);
        jdbc.update("""
                insert into operations.operator_role_assignments
                    (operator_account_id, role_id, catalog_version, granted_by_operator_id, granted_at)
                values (?, ?, ?, ?, clock_timestamp())
                """, operator, role, catalogVersion, operator);
        return operator;
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(TransactionalOutboxStore.class)
    static class TestApplication {}
}
