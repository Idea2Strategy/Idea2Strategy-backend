package com.idea2strategy.backend.persistence.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.persistence.batch.DurableBatchStore.BatchConflictException;
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
@SpringBootTest(classes = DurableBatchStoreIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DurableBatchStoreIntegrationTest {
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

    @Autowired DurableBatchStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from operations.batch_run_checkpoints");
        jdbc.update("delete from operations.batch_item_attempts");
        jdbc.update("delete from operations.batch_items");
        jdbc.update("delete from operations.batch_runs");
        jdbc.update("delete from operations.batch_job_versions");
    }

    @Test
    void discoveryIsStableAndTwoWorkersCannotOwnTheSameCurrentItem() throws Exception {
        UUID runId = activeRun("trigger-claim");
        assertThatThrownBy(() -> store.discover(
                runId, "UNKNOWN_CATEGORY", "unknown-1", "version-1",
                Instant.now().minusSeconds(1), UUID.randomUUID()))
                .isInstanceOf(BatchConflictException.class);
        assertThat(count("select count(*) from operations.batch_items")).isZero();
        UUID correlation = UUID.randomUUID();
        Instant dueAt = Instant.now().minusSeconds(1);
        UUID itemId = store.discover(runId, "CASE_RESPONSE_DEADLINE", "case-1", "version-2",
                dueAt, correlation);
        assertThat(store.discover(runId, "CASE_RESPONSE_DEADLINE", "case-1", "version-2",
                dueAt, UUID.randomUUID())).isEqualTo(itemId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.claimDue(
                    "CASE_RESPONSE_DEADLINE", "worker-a", "policy-v1", Duration.ofSeconds(5), 1));
            var second = executor.submit(() -> store.claimDue(
                    "CASE_RESPONSE_DEADLINE", "worker-b", "policy-v1", Duration.ofSeconds(5), 1));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.size() + b.size()).isEqualTo(1);
            var claim = a.isEmpty() ? b.getFirst() : a.getFirst();
            assertThat(claim.itemId()).isEqualTo(itemId);
            assertThat(claim.attemptNumber()).isEqualTo(1);
        }
    }

    @Test
    void expiredLeaseIsClosedAndItsLateCompletionIsRejected() throws Exception {
        UUID runId = activeRun("trigger-recovery");
        UUID itemId = store.discover(runId, "SESSION_EXPIRY", "session-1", "version-1",
                Instant.now().minusSeconds(1), UUID.randomUUID());
        var first = store.claimDue("SESSION_EXPIRY", "worker-a", "policy-v1",
                Duration.ofMillis(80), 1).getFirst();
        Thread.sleep(120);
        var recovered = store.claimDue("SESSION_EXPIRY", "worker-b", "policy-v2",
                Duration.ofSeconds(5), 1).getFirst();

        assertThat(recovered.attemptNumber()).isEqualTo(2);
        assertThatThrownBy(() -> store.succeed(itemId, first.claimToken(), "APPLIED"))
                .isInstanceOf(BatchConflictException.class);
        store.succeed(itemId, recovered.claimToken(), "ALREADY_APPLIED");

        assertThat(text("select status::text from operations.batch_items where id = ?", itemId))
                .isEqualTo("SUCCEEDED");
        assertThat(count("select count(*) from operations.batch_item_attempts where batch_item_id = ? and outcome = 'LEASE_EXPIRED'", itemId))
                .isEqualTo(1);
        assertThat(count("select count(*) from operations.batch_item_attempts where batch_item_id = ? and outcome = 'SUCCEEDED'", itemId))
                .isEqualTo(1);
    }

    @Test
    void retryQuarantineAndCheckpointRemainDistinctDurableEvidence() {
        UUID runId = activeRun("trigger-failure");
        UUID retryItem = store.discover(runId, "SANCTION_EXPIRY", "sanction-1", "v1",
                Instant.now().minusSeconds(1), UUID.randomUUID());
        var first = store.claimDue("SANCTION_EXPIRY", "worker-a", "policy-v1",
                Duration.ofSeconds(5), 1).getFirst();
        store.retry(retryItem, first.claimToken(), "TRANSIENT_DB", Instant.now().minusSeconds(1));
        var second = store.claimDue("SANCTION_EXPIRY", "worker-b", "policy-v1",
                Duration.ofSeconds(5), 1).getFirst();
        store.quarantine(retryItem, second.claimToken(), "RETRY_EXHAUSTED");

        store.saveCheckpoint("deadline", "v1", "SANCTION_EXPIRY", "default",
                Instant.parse("2026-08-03T00:00:00Z"), "sanction-1", runId, 17);
        store.saveCheckpoint("deadline", "v1", "SANCTION_EXPIRY", "default",
                Instant.parse("2026-08-03T00:05:00Z"), "sanction-2", runId, 23);

        assertThat(text("select status::text from operations.batch_items where id = ?", retryItem))
                .isEqualTo("QUARANTINED");
        assertThat(text("select terminal_failure_code from operations.batch_items where id = ?", retryItem))
                .isEqualTo("RETRY_EXHAUSTED");
        assertThat(count("select count(*) from operations.batch_item_attempts where batch_item_id = ?", retryItem))
                .isEqualTo(2);
        assertThat(count("select scanned_count from operations.batch_run_checkpoints where job_code = 'deadline' and job_version = 'v1' and category_code = 'SANCTION_EXPIRY' and shard_key = 'default'"))
                .isEqualTo(23);
    }

    private UUID activeRun(String triggerId) {
        store.publishJobVersion("deadline", "v1", "[\"SESSION_EXPIRY\",\"SANCTION_EXPIRY\",\"CASE_RESPONSE_DEADLINE\"]", "hash-v1");
        return store.startRun("deadline", "v1", "policy-v1", triggerId,
                Instant.now().minusSeconds(60), Instant.now());
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(DurableBatchStore.class)
    static class TestApplication {}
}
