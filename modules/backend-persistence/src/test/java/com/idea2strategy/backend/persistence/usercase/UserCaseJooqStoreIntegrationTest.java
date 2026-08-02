package com.idea2strategy.backend.persistence.usercase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.usercase.UserCaseCommand;
import com.idea2strategy.backend.application.usercase.UserCaseEvidenceOwnershipPort;
import com.idea2strategy.backend.application.usercase.UserCaseEvidenceReference;
import com.idea2strategy.backend.application.usercase.UserCaseStore;
import com.idea2strategy.backend.application.usercase.UserCaseSupplementCommand;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import com.idea2strategy.backend.application.usercase.VerifiedUserCaseEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = UserCaseJooqStoreIntegrationTest.TestApplication.class)
class UserCaseJooqStoreIntegrationTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID OTHER_ACCOUNT = id(2);
    private static final UUID OBJECT = id(3);
    private static final UUID SOURCE = id(4);
    private static final Instant NOW = Instant.parse("2026-08-02T15:00:00Z");

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

    @Autowired UserCaseJooqStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.execute("truncate table operations.case_command_receipts, "
                + "operations.case_evidence_references, operations.case_events, operations.cases cascade");
        jdbc.update("delete from operations.outbox_messages where owner_domain in ('OPERATIONS_CASE', 'TEST')");
        jdbc.update("delete from storage.objects where id = ?", OBJECT);
        jdbc.update("""
                insert into identity.accounts (id, lifecycle_status)
                values (?, 'ACTIVE'), (?, 'ACTIVE')
                on conflict (id) do update set lifecycle_status = 'ACTIVE'
                """, ACCOUNT, OTHER_ACCOUNT);
        jdbc.update("""
                insert into storage.objects
                    (id, status, storage_provider, bucket_name, object_key, provider_version_id,
                     content_hash, byte_size, file_format, compression_codec, media_type,
                     schema_version, retention_policy_version, verified_at)
                values (?, 'AVAILABLE', 'TEST', 'private', 'object', 'v1', ?, 1,
                        'JSON', 'NONE', 'application/json', '1', 'test', ?)
                """, OBJECT, "a".repeat(64), NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void duplicateSubmitCommitsExactlyOneHeadReceiptEvidenceAndOutboxMessage() throws Exception {
        UserCaseCommand command = submit("duplicate", "a".repeat(64));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.submit(command, NOW));
            var second = executor.submit(() -> store.submit(command, NOW));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.outcome(), b.outcome()))
                    .containsExactlyInAnyOrder(
                            UserCaseStore.CommandResult.Outcome.APPLIED,
                            UserCaseStore.CommandResult.Outcome.REPLAYED);
            assertThat(a.view()).isEqualTo(b.view());
        }
        assertThat(count("select count(*) from operations.cases")).isOne();
        assertThat(count("select count(*) from operations.case_events")).isOne();
        assertThat(count("select count(*) from operations.case_command_receipts")).isOne();
        assertThat(count("select count(*) from operations.case_evidence_references")).isOne();
        assertThat(count("select count(*) from operations.outbox_messages where owner_domain = 'OPERATIONS_CASE'"))
                .isOne();
    }

    @Test
    void rejectsDifferentHashAndHidesAnotherAccountsCase() {
        var applied = store.submit(submit("same", "a".repeat(64)), NOW);
        var conflict = store.submit(submit("same", "b".repeat(64)), NOW);

        assertThat(conflict.outcome()).isEqualTo(UserCaseStore.CommandResult.Outcome.IDEMPOTENCY_CONFLICT);
        assertThat(store.findOwned(OTHER_ACCOUNT, applied.view().id())).isEmpty();
        assertThat(store.findOwned(ACCOUNT, applied.view().id())).isPresent();
    }

    @Test
    void unavailableOrUnownedEvidenceUsesOneNonEnumeratingFailureWithoutSideEffects() {
        UserCaseEvidenceReference unavailable =
                new UserCaseEvidenceReference(OBJECT, "UNKNOWN_SOURCE", SOURCE);
        UserCaseCommand command = new UserCaseCommand(
                ACCOUNT, UserCaseType.INQUIRY, "Question", "Details", List.of(unavailable),
                "unavailable", "e".repeat(64), UUID.randomUUID());

        var result = store.submit(command, NOW);

        assertThat(result.outcome()).isEqualTo(UserCaseStore.CommandResult.Outcome.RESOURCE_NOT_AVAILABLE);
        assertThat(count("select count(*) from operations.cases")).isZero();
        assertThat(count("select count(*) from operations.case_events")).isZero();
        assertThat(count("select count(*) from operations.case_command_receipts")).isZero();
        assertThat(count("select count(*) from operations.outbox_messages where owner_domain = 'OPERATIONS_CASE'"))
                .isZero();
    }

    @Test
    void supplementExtendsTheLockedHeadAndAppendOnlyHistoryRejectsMutation() {
        var submitted = store.submit(new UserCaseCommand(
                ACCOUNT, UserCaseType.REPORT, "Problem", "Details", List.of(),
                "head", "a".repeat(64), UUID.randomUUID()), NOW).view();
        UUID requestEvent = UUID.randomUUID();
        jdbc.update("""
                insert into operations.case_events
                    (id, case_id, account_id, event_sequence, previous_event_id, actor_type,
                     actor_id, event_type, resulting_status, visibility, correlation_id,
                     payload_document, created_at)
                values (?, ?, ?, 2, ?, 'SYSTEM', ?, 'INFORMATION_REQUESTED',
                        'NEEDS_INFORMATION', 'USER_VISIBLE', ?, '{}'::jsonb, ?)
                """, requestEvent, submitted.id(), ACCOUNT, head(submitted.id()), ACCOUNT,
                UUID.randomUUID(), NOW.plusSeconds(1).atOffset(ZoneOffset.UTC));
        jdbc.update("""
                update operations.cases set status = 'NEEDS_INFORMATION', case_version = 2,
                    current_event_sequence = 2, last_case_event_id = ?, updated_at = ? where id = ?
                """, requestEvent, NOW.plusSeconds(1).atOffset(ZoneOffset.UTC), submitted.id());

        var result = store.supplement(new UserCaseSupplementCommand(
                ACCOUNT, submitted.id(), 2, List.of(evidence()), "supplement", "c".repeat(64),
                UUID.randomUUID()), NOW.plusSeconds(2));

        assertThat(result.outcome()).isEqualTo(UserCaseStore.CommandResult.Outcome.APPLIED);
        assertThat(result.view().version()).isEqualTo(3);
        assertThat(text("select status::text from operations.cases where id = ?", submitted.id()))
                .isEqualTo("OPEN");
        assertThat(count("select count(*) from operations.case_events where case_id = ?", submitted.id()))
                .isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update(
                "update operations.case_events set payload_document = '{}' where id = ?", requestEvent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxFailureRollsBackCaseEventEvidenceAndReceiptTogether() {
        String key = "rollback";
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, event_type, event_schema_version,
                     payload_document, idempotency_key)
                values (?, 'TEST', ?, 'BLOCK', '1', '{}'::jsonb, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), "case:" + ACCOUNT + ":SUBMIT:" + key);

        assertThatThrownBy(() -> store.submit(submit(key, "d".repeat(64)), NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("select count(*) from operations.cases")).isZero();
        assertThat(count("select count(*) from operations.case_events")).isZero();
        assertThat(count("select count(*) from operations.case_command_receipts")).isZero();
        assertThat(count("select count(*) from operations.case_evidence_references")).isZero();
    }

    private UserCaseCommand submit(String key, String hash) {
        return new UserCaseCommand(ACCOUNT, UserCaseType.REPORT, "Problem", "Details",
                List.of(evidence()), key, hash, UUID.randomUUID());
    }

    private UserCaseEvidenceReference evidence() {
        return new UserCaseEvidenceReference(OBJECT, "BACKTEST_RUN", SOURCE);
    }

    private UUID head(UUID caseId) {
        return jdbc.queryForObject(
                "select last_case_event_id from operations.cases where id = ?", UUID.class, caseId);
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private String text(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a1910000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(UserCaseJooqStore.class)
    static class TestApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        UserCaseEvidenceOwnershipPort evidenceOwnership(JdbcTemplate jdbc) {
            return (accountId, evidence, at) -> {
                Boolean available = jdbc.queryForObject(
                        "select status = 'AVAILABLE' from storage.objects where id = ?",
                        Boolean.class, evidence.storageObjectId());
                if (!Boolean.TRUE.equals(available)
                        || !"BACKTEST_RUN".equals(evidence.sourceDomain())
                        || !SOURCE.equals(evidence.sourceResourceId())) return java.util.Optional.empty();
                return java.util.Optional.of(new VerifiedUserCaseEvidence(
                        evidence.storageObjectId(), evidence.sourceDomain(), evidence.sourceResourceId(),
                        accountId, "test-policy-v1", at));
            };
        }
    }
}
