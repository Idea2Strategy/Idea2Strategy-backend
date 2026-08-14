package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class OperatorCaseCommandService {
    private final OperatorCaseCommandPort commands;
    private final OperatorCaseAuthorizationPort authorization;
    private final OperatorCaseAssigneePort assignees;
    private final CaseSanctionCommandPort sanctions;
    private final CaseNotificationOutboxPort notifications;
    private final OperatorEvidenceRedactor redactor;
    private final CaseResponseDeadlinePolicy deadlinePolicy;
    private final Clock clock;

    public OperatorCaseCommandService(
            OperatorCaseCommandPort commands,
            OperatorCaseAuthorizationPort authorization,
            OperatorCaseAssigneePort assignees,
            CaseSanctionCommandPort sanctions,
            CaseNotificationOutboxPort notifications,
            OperatorEvidenceRedactor redactor,
            CaseResponseDeadlinePolicy deadlinePolicy,
            Clock clock) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.assignees = Objects.requireNonNull(assignees, "assignees");
        this.sanctions = Objects.requireNonNull(sanctions, "sanctions");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OperatorCaseDecisionResult execute(OperatorCaseCommand command) {
        Objects.requireNonNull(command, "command");
        if (!command.requestContext().sessionAuthenticated()) {
            throw new OperatorCaseAuthenticationRejectedException();
        }
        Instant now = clock.instant();
        return commands.executeAtomically(command, now,
                state -> decide(command, state, state.databaseNow()));
    }

    private OperatorCaseDecisionResult decide(
            OperatorCaseCommand command, OperatorCaseState state, Instant now) {
        if (!state.caseView().id().equals(command.caseId())) {
            throw new IllegalStateException("CASE_STORE_SCOPE_VIOLATION");
        }
        OperatorCaseAuthorizationPort.Decision guard = authorization.authorize(
                command.requestContext(),
                command.requiredPermissionId(),
                state.caseView().type(),
                command.action().name(),
                now);
        if (!guard.granted()) {
            return rejected(command, state, now, guard.code(), guard, null);
        }
        if (state.caseView().version() != command.expectedVersion()) {
            return rejected(command, state, now, "STALE_CASE_VERSION", guard, null);
        }
        List<OperatorEvidenceView> evidence = redactor.redact(state.evidence());
        if (!evidenceAvailable(command, state)) {
            return rejected(command, state, now, "EVIDENCE_NOT_AVAILABLE", guard, null);
        }
        return switch (command.action()) {
            case ASSIGN -> assign(command, state, now, guard, evidence, false);
            case REASSIGN -> assign(command, state, now, guard, evidence, true);
            case UNASSIGN -> unassign(command, state, now, guard, evidence);
            case START_REVIEW -> transition(command, state, now, guard, evidence,
                    UserCaseStatus.OPEN, UserCaseStatus.UNDER_REVIEW, "CASE_REVIEW_STARTED", false);
            case REQUEST_INFORMATION -> requestInformation(command, state, now, guard, evidence);
            case RESOLVE -> transition(command, state, now, guard, evidence,
                    UserCaseStatus.UNDER_REVIEW, UserCaseStatus.RESOLVED, "CASE_RESOLVED", true);
            case REJECT -> transition(command, state, now, guard, evidence,
                    UserCaseStatus.UNDER_REVIEW, UserCaseStatus.REJECTED, "CASE_REJECTED", true);
            case APPLY_SANCTION, RELEASE_SANCTION -> sanction(command, state, now, guard, evidence);
        };
    }

    private OperatorCaseDecisionResult requestInformation(
            OperatorCaseCommand command, OperatorCaseState state, Instant now,
            OperatorCaseAuthorizationPort.Decision guard, List<OperatorEvidenceView> evidence) {
        if (!isCurrentAssignee(command, state)) {
            return rejected(command, state, now, "CASE_ASSIGNEE_REQUIRED", guard, null);
        }
        if (state.caseView().status().terminal()
                || (state.caseView().status() != UserCaseStatus.OPEN
                    && state.caseView().status() != UserCaseStatus.UNDER_REVIEW)) {
            return rejected(command, state, now, "CASE_TRANSITION_NOT_ALLOWED", guard, null);
        }
        Instant deadline = deadlinePolicy.deadlineFrom(state.databaseNow());
        return applied(command, state, now, guard, evidence, state.assigneeOperatorId(),
                UserCaseStatus.NEEDS_INFORMATION, "CASE_INFORMATION_REQUESTED", null, true,
                deadline, deadlinePolicy.version());
    }

    private OperatorCaseDecisionResult assign(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            OperatorCaseAuthorizationPort.Decision guard,
            List<OperatorEvidenceView> evidence,
            boolean reassignment) {
        if (!assignees.isActiveAssignableOperator(command.assigneeOperatorId(), now)) {
            return rejected(command, state, now, "ASSIGNEE_NOT_AVAILABLE", guard, null);
        }
        if (!reassignment && state.assigneeOperatorId() != null) {
            if (state.assigneeOperatorId().equals(command.assigneeOperatorId())) {
                return noOp(command, state, now, "CASE_ALREADY_ASSIGNED", guard, evidence);
            }
            return rejected(command, state, now, "CASE_ALREADY_ASSIGNED", guard, null);
        }
        if (reassignment && state.assigneeOperatorId() == null) {
            return rejected(command, state, now, "CASE_NOT_ASSIGNED", guard, null);
        }
        if (reassignment && state.assigneeOperatorId().equals(command.assigneeOperatorId())) {
            return noOp(command, state, now, "CASE_ALREADY_ASSIGNED", guard, evidence);
        }
        return applied(command, state, now, guard, evidence,
                command.assigneeOperatorId(), state.caseView().status(),
                reassignment ? "CASE_REASSIGNED" : "CASE_ASSIGNED", null, false);
    }

    private OperatorCaseDecisionResult unassign(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            OperatorCaseAuthorizationPort.Decision guard,
            List<OperatorEvidenceView> evidence) {
        if (state.assigneeOperatorId() == null) {
            return noOp(command, state, now, "CASE_ALREADY_UNASSIGNED", guard, evidence);
        }
        return applied(command, state, now, guard, evidence, null,
                state.caseView().status(), "CASE_UNASSIGNED", null, false);
    }

    private OperatorCaseDecisionResult transition(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            OperatorCaseAuthorizationPort.Decision guard,
            List<OperatorEvidenceView> evidence,
            UserCaseStatus required,
            UserCaseStatus target,
            String eventType,
            boolean notifyUser) {
        if (!isCurrentAssignee(command, state)) {
            return rejected(command, state, now, "CASE_ASSIGNEE_REQUIRED", guard, null);
        }
        if (state.caseView().status() != required || state.caseView().status().terminal()) {
            return rejected(command, state, now, "CASE_TRANSITION_NOT_ALLOWED", guard, null);
        }
        return applied(command, state, now, guard, evidence,
                state.assigneeOperatorId(), target, eventType, null, notifyUser);
    }

    private OperatorCaseDecisionResult sanction(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            OperatorCaseAuthorizationPort.Decision guard,
            List<OperatorEvidenceView> evidence) {
        if (!isCurrentAssignee(command, state)) {
            return rejected(command, state, now, "CASE_ASSIGNEE_REQUIRED", guard, null);
        }
        if (state.caseView().status() != UserCaseStatus.UNDER_REVIEW) {
            return rejected(command, state, now, "CASE_TRANSITION_NOT_ALLOWED", guard, null);
        }
        CaseSanctionCommandPort.Operation operation = command.action() == OperatorCaseCommand.Action.APPLY_SANCTION
                ? CaseSanctionCommandPort.Operation.APPLY
                : CaseSanctionCommandPort.Operation.RELEASE;
        CaseSanctionCommandPort.Result result = sanctions.execute(new CaseSanctionCommandPort.Request(
                operation,
                command.sanctionId(),
                state.caseView().accountId(),
                command.caseId(),
                command.expectedVersion(),
                command.expectedSanctionVersion(),
                command.requestContext(),
                command.sanctionType(),
                command.sanctionExpiresAt(),
                command.reasonCode(),
                command.correlationId(),
                command.idempotencyKey(),
                command.requestHash()));
        if (result.status() == CaseSanctionCommandPort.Result.Status.UNKNOWN) {
            return rejected(command, state, now, "SANCTION_RESULT_UNKNOWN", guard, null);
        }
        if (result.status() == CaseSanctionCommandPort.Result.Status.REJECTED) {
            return rejected(command, state, now, result.code(), guard, result.resultReference());
        }
        if (result.resultReference() == null || result.resultReference().isBlank()) {
            return rejected(command, state, now, "SANCTION_RESULT_INCOMPLETE", guard, null);
        }
        String eventType = operation == CaseSanctionCommandPort.Operation.APPLY
                ? "CASE_SANCTION_APPLIED"
                : "CASE_SANCTION_RELEASED";
        return applied(command, state, now, guard, evidence,
                state.assigneeOperatorId(), UserCaseStatus.RESOLVED,
                eventType, result.resultReference(), true);
    }

    private OperatorCaseDecisionResult applied(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            OperatorCaseAuthorizationPort.Decision guard,
            List<OperatorEvidenceView> evidence,
            UUID assignee,
            UserCaseStatus targetStatus,
            String eventType,
            String sanctionReference,
            boolean notifyUser) {
        return applied(command, state, now, guard, evidence, assignee, targetStatus,
                eventType, sanctionReference, notifyUser, null, null);
    }

    private OperatorCaseDecisionResult applied(
            OperatorCaseCommand command, OperatorCaseState state, Instant now,
            OperatorCaseAuthorizationPort.Decision guard, List<OperatorEvidenceView> evidence,
            UUID assignee, UserCaseStatus targetStatus, String eventType,
            String sanctionReference, boolean notifyUser, Instant responseDeadlineAt,
            String deadlinePolicyVersion) {
        long nextVersion = state.caseView().version() + 1;
        if (notifyUser) {
            notifications.stageInCurrentTransaction(new CaseNotificationOutboxPort.Intent(
                    command.caseId(),
                    state.caseView().accountId(),
                    eventType,
                    nextVersion,
                    command.correlationId(),
                    "case-notification:" + command.idempotencyKey(),
                    Map.of("caseId", command.caseId().toString(), "status", targetStatus.name())));
        }
        var mutation = new OperatorCaseDecisionResult.Mutation(
                assignee, targetStatus, nextVersion, eventType, sanctionReference,
                responseDeadlineAt, deadlinePolicyVersion);
        return new OperatorCaseDecisionResult(
                OperatorCaseDecisionResult.Status.APPLIED,
                eventType,
                mutation,
                audit(command, state, now, guard, evidence, assignee, targetStatus, nextVersion, sanctionReference));
    }

    private OperatorCaseDecisionResult noOp(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            String code,
            OperatorCaseAuthorizationPort.Decision guard,
            List<OperatorEvidenceView> evidence) {
        return new OperatorCaseDecisionResult(
                OperatorCaseDecisionResult.Status.NO_OP,
                code,
                null,
                audit(command, state, now, guard, evidence, state.assigneeOperatorId(),
                        state.caseView().status(), state.caseView().version(), null));
    }

    private OperatorCaseDecisionResult rejected(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            String code,
            OperatorCaseAuthorizationPort.Decision guard,
            String sanctionReference) {
        return new OperatorCaseDecisionResult(
                OperatorCaseDecisionResult.Status.REJECTED,
                code,
                null,
                audit(command, state, now, guard, redactor.redact(state.evidence()),
                        state.assigneeOperatorId(), state.caseView().status(),
                        state.caseView().version(), sanctionReference));
    }

    private static OperatorCaseDecisionResult.AuditEvidence audit(
            OperatorCaseCommand command,
            OperatorCaseState state,
            Instant now,
            OperatorCaseAuthorizationPort.Decision guard,
            List<OperatorEvidenceView> evidence,
            UUID afterAssignee,
            UserCaseStatus afterStatus,
            long afterVersion,
            String sanctionReference) {
        return new OperatorCaseDecisionResult.AuditEvidence(
                command.requestContext().operatorId(),
                command.caseId(),
                command.action().name(),
                command.reasonCode(),
                command.correlationId(),
                guard == null ? null : guard.rbacCatalogVersion(),
                state.caseView().version(),
                afterVersion,
                state.assigneeOperatorId(),
                afterAssignee,
                state.caseView().status(),
                afterStatus,
                evidence,
                sanctionReference,
                now);
    }

    private static boolean evidenceAvailable(OperatorCaseCommand command, OperatorCaseState state) {
        if (command.evidenceIds().isEmpty()) {
            return true;
        }
        return command.evidenceIds().stream().allMatch(id -> state.evidence().stream().anyMatch(item ->
                item.evidenceId().equals(id)
                        && item.ownershipVerified()
                        && "AVAILABLE".equals(item.status())));
    }

    private static boolean isCurrentAssignee(OperatorCaseCommand command, OperatorCaseState state) {
        return state.assigneeOperatorId() != null
                && state.assigneeOperatorId().equals(command.requestContext().operatorId());
    }
}
