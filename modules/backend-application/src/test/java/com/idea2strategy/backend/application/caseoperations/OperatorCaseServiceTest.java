package com.idea2strategy.backend.application.caseoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import com.idea2strategy.backend.application.usercase.UserCaseView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OperatorCaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T17:00:00Z");
    private static final UUID OPERATOR = uuid(1);
    private static final UUID OTHER_OPERATOR = uuid(2);
    private static final UUID ACCOUNT = uuid(3);
    private static final UUID CASE_ID = uuid(4);
    private static final UUID EVIDENCE_ID = uuid(5);
    private static final UUID PERMISSION = uuid(6);
    private static final UUID SANCTION_ID = uuid(7);

    @Test
    void authorizesEveryRequestedQueueTypeAndRedactsDetailEvidence() {
        var fixture = fixture(state(UserCaseStatus.OPEN, 1, null));
        var queryService = fixture.queryService();
        var request = new OperatorCaseQueueRequest(
                trusted(), PERMISSION, Set.of(UserCaseType.REPORT, UserCaseType.APPEAL),
                Set.of(UserCaseStatus.OPEN), null, null, 20);

        OperatorCaseQueuePort.Page page = queryService.queue(request);
        OperatorCaseDetail detail = queryService.detail(trusted(), PERMISSION, CASE_ID);

        assertThat(page.items()).hasSize(1);
        assertThat(detail.subject()).isEqualTo("의심스러운 거래 신고");
        assertThat(detail.description()).isEqualTo("체결 내역과 결과를 확인해 주세요.");
        assertThat(fixture.authorization.calls).extracting(AuthorizationCall::type)
                .contains(UserCaseType.REPORT, UserCaseType.APPEAL);
        assertThat(detail.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.attributes()).containsEntry("summaryCode", "MATCHED_POLICY");
            assertThat(evidence.attributes()).doesNotContainKeys(
                    "privateStrategySource", "positionQuantity", "orderPayload");
        });
    }

    @Test
    void deniesTheWholeQueueWhenAnyRequestedCaseTypeIsNotAuthorized() {
        var fixture = fixture(state(UserCaseStatus.OPEN, 1, null));
        fixture.authorization.deniedType = UserCaseType.APPEAL;

        assertThatThrownBy(() -> fixture.queryService().queue(new OperatorCaseQueueRequest(
                        trusted(), PERMISSION, Set.of(UserCaseType.REPORT, UserCaseType.APPEAL),
                        Set.of(), null, null, 20)))
                .isInstanceOf(OperatorCaseQueryRejectedException.class)
                .hasMessage("CASE_PERMISSION_DENIED");
        assertThat(fixture.queue.queries).isEmpty();
    }

    @Test
    void assignsReassignsAndUnassignsWithOptimisticVersionedMutations() {
        var assignFixture = fixture(state(UserCaseStatus.OPEN, 3, null));
        OperatorCaseDecisionResult assigned = assignFixture.commandService().execute(command(
                OperatorCaseCommand.Action.ASSIGN, 3, OTHER_OPERATOR, null, "assign", hash('a')));
        assertThat(assigned.mutation().assigneeOperatorId()).isEqualTo(OTHER_OPERATOR);
        assertThat(assigned.mutation().nextVersion()).isEqualTo(4);

        var reassignFixture = fixture(state(UserCaseStatus.OPEN, 4, OTHER_OPERATOR));
        OperatorCaseDecisionResult reassigned = reassignFixture.commandService().execute(command(
                OperatorCaseCommand.Action.REASSIGN, 4, OPERATOR, null, "reassign", hash('b')));
        assertThat(reassigned.mutation().assigneeOperatorId()).isEqualTo(OPERATOR);
        assertThat(reassigned.code()).isEqualTo("CASE_REASSIGNED");

        var unassignFixture = fixture(state(UserCaseStatus.OPEN, 5, OPERATOR));
        OperatorCaseDecisionResult unassigned = unassignFixture.commandService().execute(command(
                OperatorCaseCommand.Action.UNASSIGN, 5, null, null, "unassign", hash('c')));
        assertThat(unassigned.mutation().assigneeOperatorId()).isNull();
        assertThat(unassigned.code()).isEqualTo("CASE_UNASSIGNED");
    }

    @Test
    void rejectsStaleAndInactiveAssigneeCommandsWithoutMutation() {
        var staleFixture = fixture(state(UserCaseStatus.OPEN, 4, null));
        OperatorCaseDecisionResult stale = staleFixture.commandService().execute(command(
                OperatorCaseCommand.Action.ASSIGN, 3, OTHER_OPERATOR, null, "stale", hash('d')));
        assertThat(stale.code()).isEqualTo("STALE_CASE_VERSION");
        assertThat(stale.auditEvidence().beforeVersion()).isEqualTo(stale.auditEvidence().afterVersion());

        var inactiveFixture = fixture(state(UserCaseStatus.OPEN, 4, null));
        inactiveFixture.assignees.assignable = false;
        OperatorCaseDecisionResult inactive = inactiveFixture.commandService().execute(command(
                OperatorCaseCommand.Action.ASSIGN, 4, OTHER_OPERATOR, null, "inactive", hash('e')));
        assertThat(inactive.code()).isEqualTo("ASSIGNEE_NOT_AVAILABLE");
        assertThat(inactiveFixture.commands.mutations).isEmpty();
    }

    @Test
    void enforcesAssigneeOwnedStateTransitionsAndStagesPublicNotificationOutbox() {
        var fixture = fixture(state(UserCaseStatus.UNDER_REVIEW, 8, OPERATOR));

        OperatorCaseDecisionResult result = fixture.commandService().execute(command(
                OperatorCaseCommand.Action.REQUEST_INFORMATION, 8, null, null,
                "request-info", hash('f')));

        assertThat(result.mutation().status()).isEqualTo(UserCaseStatus.NEEDS_INFORMATION);
        assertThat(result.mutation().nextVersion()).isEqualTo(9);
        assertThat(result.mutation().responseDeadlineAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(result.mutation().deadlinePolicyVersion()).isEqualTo("case-response-v1");
        assertThat(fixture.notifications.intents).singleElement().satisfies(intent -> {
            assertThat(intent.eventType()).isEqualTo("CASE_INFORMATION_REQUESTED");
            assertThat(intent.publicPayload()).containsOnlyKeys("caseId", "status");
        });
        assertThat(result.auditEvidence().beforeStatus()).isEqualTo(UserCaseStatus.UNDER_REVIEW);
        assertThat(result.auditEvidence().afterStatus()).isEqualTo(UserCaseStatus.NEEDS_INFORMATION);
    }

    @Test
    void canRequestInformationDirectlyFromOpenWithoutCallerSuppliedDuration() {
        var fixture = fixture(state(UserCaseStatus.OPEN, 2, OPERATOR));

        OperatorCaseDecisionResult result = fixture.commandService().execute(command(
                OperatorCaseCommand.Action.REQUEST_INFORMATION, 2, null, null,
                "request-open", hash('0')));

        assertThat(result.status()).isEqualTo(OperatorCaseDecisionResult.Status.APPLIED);
        assertThat(result.mutation().status()).isEqualTo(UserCaseStatus.NEEDS_INFORMATION);
        assertThat(result.mutation().responseDeadlineAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void rejectsTerminalOrNonAssigneeTransitions() {
        var terminal = fixture(state(UserCaseStatus.RESOLVED, 9, OPERATOR));
        OperatorCaseDecisionResult terminalResult = terminal.commandService().execute(command(
                OperatorCaseCommand.Action.RESOLVE, 9, null, null, "terminal", hash('1')));
        assertThat(terminalResult.code()).isEqualTo("CASE_TRANSITION_NOT_ALLOWED");

        var otherAssignee = fixture(state(UserCaseStatus.UNDER_REVIEW, 9, OTHER_OPERATOR));
        OperatorCaseDecisionResult denied = otherAssignee.commandService().execute(command(
                OperatorCaseCommand.Action.RESOLVE, 9, null, null, "not-assignee", hash('2')));
        assertThat(denied.code()).isEqualTo("CASE_ASSIGNEE_REQUIRED");
    }

    @Test
    void doesNotAdvanceTheCaseWhenSanctionResultIsRejectedOrUnknown() {
        var unknownFixture = fixture(state(UserCaseStatus.UNDER_REVIEW, 10, OPERATOR));
        unknownFixture.sanctions.result = new CaseSanctionCommandPort.Result(
                CaseSanctionCommandPort.Result.Status.UNKNOWN, "TIMEOUT", null);
        OperatorCaseDecisionResult unknown = unknownFixture.commandService().execute(command(
                OperatorCaseCommand.Action.RELEASE_SANCTION, 10, null, SANCTION_ID,
                "sanction-unknown", hash('3')));
        assertThat(unknown.code()).isEqualTo("SANCTION_RESULT_UNKNOWN");
        assertThat(unknownFixture.notifications.intents).isEmpty();
        assertThat(unknownFixture.commands.mutations).isEmpty();

        var rejectedFixture = fixture(state(UserCaseStatus.UNDER_REVIEW, 10, OPERATOR));
        rejectedFixture.sanctions.result = new CaseSanctionCommandPort.Result(
                CaseSanctionCommandPort.Result.Status.REJECTED, "SANCTION_POLICY_REJECTED", "sanction-result-1");
        OperatorCaseDecisionResult rejected = rejectedFixture.commandService().execute(command(
                OperatorCaseCommand.Action.APPLY_SANCTION, 10, null, SANCTION_ID,
                "sanction-rejected", hash('4')));
        assertThat(rejected.code()).isEqualTo("SANCTION_POLICY_REJECTED");
        assertThat(rejected.auditEvidence().sanctionResultReference()).isEqualTo("sanction-result-1");
    }

    @Test
    void resolvesOnlyAfterAReferencedSanctionCommandSucceeds() {
        var fixture = fixture(state(UserCaseStatus.UNDER_REVIEW, 10, OPERATOR));
        fixture.sanctions.result = new CaseSanctionCommandPort.Result(
                CaseSanctionCommandPort.Result.Status.APPLIED, "SANCTION_RELEASED", "sanction-result-2");

        OperatorCaseDecisionResult result = fixture.commandService().execute(command(
                OperatorCaseCommand.Action.RELEASE_SANCTION, 10, null, SANCTION_ID,
                "sanction-success", hash('5')));

        assertThat(result.status()).isEqualTo(OperatorCaseDecisionResult.Status.APPLIED);
        assertThat(result.mutation().status()).isEqualTo(UserCaseStatus.RESOLVED);
        assertThat(result.mutation().sanctionResultReference()).isEqualTo("sanction-result-2");
        assertThat(fixture.notifications.intents).hasSize(1);
    }

    @Test
    void doesNotCommitAUserVisibleTransitionWhenOutboxStagingFails() {
        var fixture = fixture(state(UserCaseStatus.UNDER_REVIEW, 10, OPERATOR));
        fixture.notifications.fail = true;

        assertThatThrownBy(() -> fixture.commandService().execute(command(
                        OperatorCaseCommand.Action.RESOLVE, 10, null, null,
                        "outbox-failure", hash('9'))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OUTBOX_UNAVAILABLE");
        assertThat(fixture.commands.mutations).isEmpty();
    }

    @Test
    void rejectsUnavailableEvidenceAndNeverCopiesSensitiveAttributesIntoAudit() {
        OperatorCaseState baseline = state(UserCaseStatus.UNDER_REVIEW, 11, OPERATOR);
        OperatorCaseState unavailable = new OperatorCaseState(
                baseline.caseView(), baseline.assigneeOperatorId(), List.of(new OperatorCaseState.Evidence(
                        EVIDENCE_ID, "OBJECT", "DELETED", "STORAGE", true, NOW,
                        Map.of("privateStrategySource", "secret"))));
        var fixture = fixture(unavailable);

        OperatorCaseDecisionResult result = fixture.commandService().execute(command(
                OperatorCaseCommand.Action.RESOLVE, 11, null, null, "evidence", hash('6')));

        assertThat(result.code()).isEqualTo("EVIDENCE_NOT_AVAILABLE");
        assertThat(result.auditEvidence().evidence()).singleElement()
                .satisfies(evidence -> assertThat(evidence.attributes()).isEmpty());
    }

    @Test
    void replaysOneDecisionConflictsOnHashMismatchAndSerializesDuplicates() throws Exception {
        var fixture = fixture(state(UserCaseStatus.OPEN, 12, null));
        OperatorCaseCommand command = command(
                OperatorCaseCommand.Action.ASSIGN, 12, OTHER_OPERATOR, null, "global", hash('7'));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fixture.commandService().execute(command));
            var second = executor.submit(() -> fixture.commandService().execute(command));
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(fixture.commands.decisions).isEqualTo(1);
        assertThat(fixture.commands.mutations).hasSize(1);
        assertThatThrownBy(() -> fixture.commandService().execute(command(
                        OperatorCaseCommand.Action.ASSIGN, 12, OTHER_OPERATOR, null, "global", hash('8'))))
                .isInstanceOf(OperatorCaseIdempotencyConflictException.class)
                .hasMessage("OPERATOR_CASE_IDEMPOTENCY_CONFLICT");
    }

    private static Fixture fixture(OperatorCaseState state) {
        var authorization = new RecordingAuthorization();
        var assignees = new RecordingAssignees();
        var sanctions = new RecordingSanctions();
        var notifications = new RecordingNotifications();
        var commands = new RecordingCommands(state);
        var queue = new RecordingQueue(state);
        var redactor = new OperatorEvidenceRedactor();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new Fixture(
                new OperatorCaseCommandService(
                        commands, authorization, assignees, sanctions, notifications, redactor,
                        new CaseResponseDeadlinePolicy("case-response-v1", Duration.ofDays(7)), clock),
                new OperatorCaseQueryService(queue, authorization, redactor, clock),
                authorization, assignees, sanctions, notifications, commands, queue);
    }

    private static OperatorCaseState state(UserCaseStatus status, long version, UUID assignee) {
        var view = new UserCaseView(
                CASE_ID, ACCOUNT, UserCaseType.REPORT, status, version, List.of(EVIDENCE_ID), NOW);
        var evidence = new OperatorCaseState.Evidence(
                EVIDENCE_ID,
                "OBJECT",
                "AVAILABLE",
                "STORAGE",
                true,
                NOW.minusSeconds(30),
                Map.of(
                        "summaryCode", "MATCHED_POLICY",
                        "privateStrategySource", "secret",
                        "positionQuantity", 100,
                        "orderPayload", Map.of("side", "BUY")));
        return new OperatorCaseState(
                view,
                assignee,
                List.of(evidence),
                "의심스러운 거래 신고",
                "체결 내역과 결과를 확인해 주세요.",
                NOW,
                null,
                null);
    }

    private static OperatorCaseCommand command(
            OperatorCaseCommand.Action action,
            long expectedVersion,
            UUID assignee,
            UUID sanctionId,
            String key,
            String hash) {
        return new OperatorCaseCommand(
                action,
                trusted(),
                CASE_ID,
                expectedVersion,
                assignee,
                PERMISSION,
                "OPERATOR_REVIEW",
                List.of(EVIDENCE_ID),
                sanctionId,
                uuid(20),
                key,
                hash);
    }

    private static OperatorRequestContext trusted() {
        return new OperatorRequestContext(OPERATOR, true, true);
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("30000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private record Fixture(
            OperatorCaseCommandService commandService,
            OperatorCaseQueryService queryService,
            RecordingAuthorization authorization,
            RecordingAssignees assignees,
            RecordingSanctions sanctions,
            RecordingNotifications notifications,
            RecordingCommands commands,
            RecordingQueue queue) {}

    private record AuthorizationCall(UserCaseType type, String action) {}

    private static final class RecordingAuthorization implements OperatorCaseAuthorizationPort {
        private UserCaseType deniedType;
        private final List<AuthorizationCall> calls = new ArrayList<>();

        @Override
        public Decision authorize(
                OperatorRequestContext context,
                UUID requiredPermissionId,
                UserCaseType caseType,
                String action,
                Instant evaluatedAt) {
            calls.add(new AuthorizationCall(caseType, action));
            return caseType == deniedType
                    ? Decision.rejected("CASE_PERMISSION_DENIED", "rbac-v1")
                    : Decision.granted("rbac-v1");
        }
    }

    private static final class RecordingAssignees implements OperatorCaseAssigneePort {
        private boolean assignable = true;

        @Override
        public boolean isActiveAssignableOperator(UUID operatorId, Instant evaluatedAt) {
            return assignable;
        }
    }

    private static final class RecordingSanctions implements CaseSanctionCommandPort {
        private Result result = new Result(Result.Status.UNKNOWN, "NOT_CONFIGURED", null);
        private final List<Request> requests = new ArrayList<>();

        @Override
        public Result execute(Request request) {
            requests.add(request);
            return result;
        }
    }

    private static final class RecordingNotifications implements CaseNotificationOutboxPort {
        private final List<Intent> intents = new ArrayList<>();
        private boolean fail;

        @Override
        public void stageInCurrentTransaction(Intent intent) {
            if (fail) {
                throw new IllegalStateException("OUTBOX_UNAVAILABLE");
            }
            intents.add(intent);
        }
    }

    private static final class RecordingCommands implements OperatorCaseCommandPort {
        private OperatorCaseState state;
        private final Map<String, Receipt> receipts = new HashMap<>();
        private final List<OperatorCaseDecisionResult.Mutation> mutations = new ArrayList<>();
        private int decisions;

        private RecordingCommands(OperatorCaseState state) {
            this.state = state;
        }

        @Override
        public synchronized OperatorCaseDecisionResult executeAtomically(
                OperatorCaseCommand command,
                Instant evaluatedAt,
                Decision decision) {
            Receipt receipt = receipts.get(command.idempotencyKey());
            if (receipt != null) {
                if (!receipt.hash.equals(command.requestHash())) {
                    throw new OperatorCaseIdempotencyConflictException();
                }
                return receipt.result;
            }
            decisions++;
            OperatorCaseDecisionResult result = decision.decide(state);
            if (result.mutation() != null) {
                mutations.add(result.mutation());
                OperatorCaseDecisionResult.Mutation mutation = result.mutation();
                UserCaseView current = state.caseView();
                state = new OperatorCaseState(
                        new UserCaseView(
                                current.id(), current.accountId(), current.type(), mutation.status(),
                                mutation.nextVersion(), current.evidenceObjectIds(), evaluatedAt),
                        mutation.assigneeOperatorId(),
                        state.evidence());
            }
            receipts.put(command.idempotencyKey(), new Receipt(command.requestHash(), result));
            return result;
        }
    }

    private record Receipt(String hash, OperatorCaseDecisionResult result) {}

    private static final class RecordingQueue implements OperatorCaseQueuePort {
        private final OperatorCaseState state;
        private final List<Query> queries = new ArrayList<>();

        private RecordingQueue(OperatorCaseState state) {
            this.state = state;
        }

        @Override
        public Page findQueue(Query query, Instant evaluatedAt) {
            queries.add(query);
            return new Page(List.of(new Item(
                    state.caseView().id(), state.caseView().type(), state.caseView().status(),
                    state.caseView().version(), state.assigneeOperatorId(), state.caseView().updatedAt())), null);
        }

        @Override
        public Optional<OperatorCaseState> findCase(UUID caseId, Instant evaluatedAt) {
            return Optional.of(state);
        }
    }
}
