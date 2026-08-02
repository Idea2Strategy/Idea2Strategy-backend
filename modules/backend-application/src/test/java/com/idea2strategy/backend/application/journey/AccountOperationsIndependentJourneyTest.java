package com.idea2strategy.backend.application.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimPage;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimRequest;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.Cursor;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ItemResult;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.WorkItem;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator.RunCommand;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommand;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommandPort;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommandType;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationDecision;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationExecution;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationIdempotencyException;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationMutation;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationResult;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationScope;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationService;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationStatus;
import com.idea2strategy.backend.application.delegation.DelegatedCredentialMaterial;
import com.idea2strategy.backend.application.testing.AccountOperationsJourneyFixture;
import com.idea2strategy.backend.application.testing.AccountOperationsJourneyFixture.FakeDomain;
import com.idea2strategy.backend.application.testing.AccountOperationsJourneyFixture.JourneyRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountOperationsIndependentJourneyTest {
    private static final Instant NOW = Instant.parse("2026-08-02T18:00:00Z");
    private static final UUID OPERATOR_PERMISSION = uuid(1);
    private static final UUID USER_CORRELATION = uuid(11);
    private static final UUID OPERATIONS_CORRELATION = uuid(12);
    private static final UUID DELIVERY_CORRELATION = uuid(13);

    @Test
    void runsTheIndependentAccountAndOperationsJourneyWithVersionedFakeIncidents() {
        var fixture = fixture();

        assertThat(fixture.registerAndVerify("signup-1", hash('a'), USER_CORRELATION))
                .isEqualTo("ACCOUNT_ACTIVE");
        assertThat(fixture.login("login-1", hash('b'), USER_CORRELATION))
                .isEqualTo("SESSION_ACTIVE");
        assertThat(fixture.updatePreferences(
                        "ko", "America/New_York", "SYSTEM", "preferences-1", hash('c'), USER_CORRELATION))
                .isEqualTo("PREFERENCES_UPDATED");

        fixture.activateOperatorMfa();
        fixture.recordFakeIncident(
                FakeDomain.STRATEGY,
                AccountOperationsJourneyFixture.STRATEGY_INCIDENT_ID,
                1,
                "STRATEGY_REFERENCE_AVAILABLE",
                OPERATIONS_CORRELATION);
        fixture.recordFakeIncident(
                FakeDomain.BOT,
                AccountOperationsJourneyFixture.BOT_INCIDENT_ID,
                2,
                "BOT_EXECUTION_BLOCKED",
                OPERATIONS_CORRELATION);
        fixture.recordFakeIncident(
                FakeDomain.ROOM,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                3,
                "ROOM_RESULT_REVIEW_REQUIRED",
                OPERATIONS_CORRELATION);
        assertThat(fixture.invokeOperatorTool(
                        OPERATOR_PERMISSION,
                        FakeDomain.ROOM,
                        AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                        3,
                        OPERATIONS_CORRELATION))
                .isEqualTo("ROOM_RESULT_REVIEW_REQUIRED");

        assertThat(fixture.submitCase(
                        AccountOperationsJourneyFixture.ACCOUNT_ID,
                        AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                        "case-submit-1",
                        hash('d'),
                        USER_CORRELATION))
                .isEqualTo("CASE_OPEN");
        assertThat(fixture.assignAndStartReview(
                        OPERATOR_PERMISSION, "case-review-1", hash('e'), OPERATIONS_CORRELATION))
                .isEqualTo("CASE_UNDER_REVIEW");
        assertThat(fixture.applySanctionAndResolveCase(
                        OPERATOR_PERMISSION, "sanction-1", hash('f'), OPERATIONS_CORRELATION))
                .isEqualTo("CASE_RESOLVED_WITH_SANCTION");

        var afterResolution = fixture.snapshot();
        assertThat(afterResolution.sanctioned()).isTrue();
        assertThat(afterResolution.sessionActive()).isFalse();
        assertThat(afterResolution.caseStatus())
                .isEqualTo(AccountOperationsJourneyFixture.CaseStatus.RESOLVED);
        assertThat(afterResolution.traces())
                .anyMatch(trace -> trace.correlationId().equals(OPERATIONS_CORRELATION)
                        && trace.stage().equals("MCP"))
                .anyMatch(trace -> trace.correlationId().equals(OPERATIONS_CORRELATION)
                        && trace.stage().equals("OUTBOX"))
                .anyMatch(trace -> trace.correlationId().equals(OPERATIONS_CORRELATION)
                        && trace.stage().equals("AUDIT"));

        fixture.liftSanction(OPERATIONS_CORRELATION);
        assertThat(fixture.login("login-after-lift", hash('6'), USER_CORRELATION))
                .isEqualTo("SESSION_ACTIVE");
        assertThat(fixture.closeAccount("close-1", hash('1'), USER_CORRELATION))
                .isEqualTo("ACCOUNT_CLOSED");
        assertThat(fixture.snapshot().accountStatus())
                .isEqualTo(AccountOperationsJourneyFixture.AccountStatus.CLOSED);
    }

    @Test
    void preservesBusinessStateAcrossNotificationFailureAndRetriesOneOutboxMessage() {
        var fixture = resolvedCaseFixture();
        int messageCount = fixture.snapshot().outboxMessageCount();

        assertThat(fixture.deliverNextNotification(false, DELIVERY_CORRELATION).code())
                .isEqualTo("RETRY_SCHEDULED");
        assertThat(fixture.snapshot().caseStatus())
                .isEqualTo(AccountOperationsJourneyFixture.CaseStatus.RESOLVED);
        assertThat(fixture.snapshot().outboxMessageCount()).isEqualTo(messageCount);

        assertThat(fixture.deliverNextNotification(true, DELIVERY_CORRELATION).code())
                .isEqualTo("DELIVERED");
        assertThat(fixture.snapshot().deliveredNotificationCount()).isEqualTo(1);
        assertThat(fixture.snapshot().traces())
                .anyMatch(trace -> trace.correlationId().equals(DELIVERY_CORRELATION)
                        && trace.action().equals("NOTIFICATION_RETRY_SCHEDULED"))
                .anyMatch(trace -> trace.correlationId().equals(DELIVERY_CORRELATION)
                        && trace.action().equals("NOTIFICATION_DELIVERED"));
    }

    @Test
    void keepsUnauthorizedAndMissingResourcesNonEnumeratingAndCommandsIdempotent() {
        var fixture = fixture();
        fixture.registerAndVerify("signup", hash('2'), USER_CORRELATION);
        fixture.login("login", hash('3'), USER_CORRELATION);
        fixture.recordFakeIncident(
                FakeDomain.ROOM,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                1,
                "ROOM_REVIEW",
                OPERATIONS_CORRELATION);

        assertThatThrownBy(() -> fixture.caseDetail(uuid(99), AccountOperationsJourneyFixture.CASE_ID))
                .isInstanceOf(JourneyRejectedException.class)
                .hasMessage("RESOURCE_NOT_AVAILABLE");
        assertThatThrownBy(() -> fixture.invokeOperatorTool(
                        uuid(98), FakeDomain.ROOM, uuid(97), 1, OPERATIONS_CORRELATION))
                .isInstanceOf(JourneyRejectedException.class)
                .hasMessage("RESOURCE_NOT_AVAILABLE");

        String first = fixture.submitCase(
                AccountOperationsJourneyFixture.ACCOUNT_ID,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                "same-key",
                hash('4'),
                USER_CORRELATION);
        String replay = fixture.submitCase(
                AccountOperationsJourneyFixture.ACCOUNT_ID,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                "same-key",
                hash('4'),
                USER_CORRELATION);
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> fixture.submitCase(
                        AccountOperationsJourneyFixture.ACCOUNT_ID,
                        AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                        "same-key",
                        hash('5'),
                        USER_CORRELATION))
                .isInstanceOf(JourneyRejectedException.class)
                .hasMessage("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void appliesDelegatedAuthorizationAndRunsItsDeadlineBatchWithReplayEvidence() {
        var fixture = fixture();
        fixture.registerAndVerify("signup-delegated", hash('a'), USER_CORRELATION);
        fixture.login("login-delegated", hash('b'), USER_CORRELATION);
        fixture.activateOperatorMfa();

        var delegatedCommand = new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.CREATE,
                AccountOperationsJourneyFixture.ACCOUNT_ID,
                uuid(31),
                null,
                0,
                4,
                "e2e-client",
                uuid(32),
                Set.of(DelegatedAuthorizationScope.STRATEGY_CREATE),
                Set.of(AccountOperationsJourneyFixture.STRATEGY_INCIDENT_ID),
                NOW.plus(Duration.ofHours(1)),
                null,
                "delegated-create",
                hash('c'),
                USER_CORRELATION);

        DelegatedAuthorizationResult first = fixture.authorizeDelegatedStrategy(delegatedCommand);
        DelegatedAuthorizationResult replay = fixture.authorizeDelegatedStrategy(delegatedCommand);
        assertThat(first.status()).isEqualTo(DelegatedAuthorizationStatus.ACTIVE);
        assertThat(first.authorizationVersion()).isEqualTo(1);
        assertThat(first.rawCredential()).contains("raw-delegated-once");
        assertThat(replay.rawCredential()).isEmpty();

        var batchCommand = new RunCommand(
                uuid(33), OPERATIONS_CORRELATION, "journey-worker", "policy-v1",
                Duration.ofMinutes(1), 1, Set.of(BatchCategory.DELEGATED_TOKEN));
        var firstBatch = fixture.runDeadlineBatch(batchCommand);
        var replayBatch = fixture.runDeadlineBatch(batchCommand);

        assertThat(firstBatch.completed()).isEqualTo(1);
        assertThat(firstBatch.categoryFailures()).isZero();
        assertThat(replayBatch.alreadyCompleted()).isEqualTo(1);
        assertThat(fixture.snapshot().traces())
                .anyMatch(trace -> trace.action().equals("DELEGATED_AUTHORIZATION_APPLIED"))
                .anyMatch(trace -> trace.action().equals("DEADLINE_BATCH_COMPLETED"));
    }

    private static AccountOperationsJourneyFixture resolvedCaseFixture() {
        var fixture = fixture();
        fixture.registerAndVerify("signup", hash('6'), USER_CORRELATION);
        fixture.login("login", hash('7'), USER_CORRELATION);
        fixture.activateOperatorMfa();
        fixture.recordFakeIncident(
                FakeDomain.ROOM,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                1,
                "ROOM_REVIEW",
                OPERATIONS_CORRELATION);
        fixture.submitCase(
                AccountOperationsJourneyFixture.ACCOUNT_ID,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                "submit",
                hash('8'),
                USER_CORRELATION);
        fixture.assignAndStartReview(OPERATOR_PERMISSION, "review", hash('9'), OPERATIONS_CORRELATION);
        fixture.applySanctionAndResolveCase(OPERATOR_PERMISSION, "sanction", hash('0'), OPERATIONS_CORRELATION);
        return fixture;
    }

    private static AccountOperationsJourneyFixture fixture() {
        return new AccountOperationsJourneyFixture(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Set.of(OPERATOR_PERMISSION),
                delegatedAuthorizationService(),
                deadlineBatchOrchestrator());
    }

    private static DelegatedAuthorizationService delegatedAuthorizationService() {
        return new DelegatedAuthorizationService(
                new JourneyDelegatedCommandPort(),
                () -> new DelegatedCredentialMaterial("raw-delegated-once", "digest-only", (short) 1),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> uuid(34));
    }

    private static DeadlineBatchOrchestrator deadlineBatchOrchestrator() {
        return new DeadlineBatchOrchestrator(
                List.of(new JourneyDeadlinePort()),
                ignored -> {},
                ignored -> {},
                1);
    }

    private static final class JourneyDelegatedCommandPort implements DelegatedAuthorizationCommandPort {
        private final Map<String, Receipt> receipts = new HashMap<>();

        @Override
        public DelegatedAuthorizationExecution executeAtomically(
                DelegatedAuthorizationCommand command,
                Instant at,
                DelegatedAuthorizationDecision decision) {
            Receipt receipt = receipts.get(command.idempotencyKey());
            if (receipt != null) {
                if (!receipt.requestHash().equals(command.requestHash())) {
                    throw new DelegatedAuthorizationIdempotencyException();
                }
                return new DelegatedAuthorizationExecution(receipt.result(), false);
            }
            DelegatedAuthorizationMutation mutation = decision.decide(Optional.empty());
            DelegatedAuthorizationResult result = mutation.toStoredResult();
            receipts.put(command.idempotencyKey(), new Receipt(command.requestHash(), result));
            return new DelegatedAuthorizationExecution(result, true);
        }

        private record Receipt(String requestHash, DelegatedAuthorizationResult result) {}
    }

    private static final class JourneyDeadlinePort implements BatchCategoryPort {
        private final Set<String> completed = new HashSet<>();

        @Override
        public BatchCategory category() {
            return BatchCategory.DELEGATED_TOKEN;
        }

        @Override
        public ClaimPage claimDue(ClaimRequest request) {
            WorkItem item = new WorkItem(
                    category(), "delegated-authorization-expiry", NOW.minusSeconds(1),
                    "delegated-expiry-1", uuid(35), 1);
            return new ClaimPage(NOW, List.of(item), new Cursor(NOW, item.itemId()));
        }

        @Override
        public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
            return completed.add(item.idempotencyKey())
                    ? ItemResult.completed()
                    : ItemResult.alreadyCompleted();
        }
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("50000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
