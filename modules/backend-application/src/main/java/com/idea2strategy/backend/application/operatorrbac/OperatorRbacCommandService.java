package com.idea2strategy.backend.application.operatorrbac;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OperatorRbacCommandService {
    private final OperatorRbacCommandPort commands;
    private final Clock clock;

    public OperatorRbacCommandService(OperatorRbacCommandPort commands, Clock clock) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OperatorRbacResult execute(OperatorRbacCommand command) {
        Objects.requireNonNull(command, "command");
        if (!command.requestContext().trustedExternalSubject()) {
            throw new OperatorRbacAuthenticationRejectedException();
        }
        Instant now = clock.instant();
        return commands.executeAtomically(command, now, state -> decide(command, state, now));
    }

    private static OperatorRbacResult decide(
            OperatorRbacCommand command, OperatorRbacState state, Instant now) {
        Evaluation evaluation = evaluateActor(command, state, now);
        if (evaluation.rejectionCode != null) {
            return rejected(evaluation.rejectionCode, evaluation.evidence());
        }
        if (command.type() == OperatorRbacCommand.Type.GRANT
                && command.expiresAt() != null
                && !now.isBefore(command.expiresAt())) {
            return rejected("INVALID_ASSIGNMENT_EXPIRY", evaluation.evidence());
        }
        if (state.target() == null || !state.target().active()) {
            return rejected("OPERATOR_NOT_FOUND", evaluation.evidence());
        }
        return command.type() == OperatorRbacCommand.Type.GRANT
                ? decideGrant(command, state, now, evaluation)
                : decideRevoke(command, state, now, evaluation);
    }

    private static OperatorRbacResult decideGrant(
            OperatorRbacCommand command,
            OperatorRbacState state,
            Instant now,
            Evaluation evaluation) {
        OperatorRbacState.Role targetRole = state.catalog().roles().get(command.roleId());
        if (targetRole == null || !targetRole.active()) {
            return rejected("ROLE_NOT_AVAILABLE", evaluation.evidence());
        }
        Evaluation withTarget = evaluation.withTargetRole(targetRole);
        String denial = delegationDenial(withTarget, targetRole);
        if (denial != null) {
            return rejected(denial, withTarget.evidence());
        }
        boolean alreadyAssigned = state.targetAssignments().stream()
                .filter(assignment -> assignment.effectiveAt(now, state.catalog()))
                .anyMatch(assignment -> assignment.roleId().equals(command.roleId()));
        if (alreadyAssigned) {
            return new OperatorRbacResult(
                    OperatorRbacResult.DecisionStatus.NO_OP,
                    "ROLE_ALREADY_ASSIGNED",
                    null,
                    withTarget.evidence());
        }
        var mutation = new OperatorRbacDecision.Mutation(
                OperatorRbacDecision.Mutation.Kind.GRANT,
                command.targetOperatorId(),
                command.roleId(),
                null,
                state.catalog().version(),
                now,
                command.expiresAt(),
                null,
                command.requestContext().operatorId(),
                command.reasonCode());
        return new OperatorRbacResult(
                OperatorRbacResult.DecisionStatus.APPLIED,
                "ROLE_GRANTED",
                mutation,
                withTarget.evidence());
    }

    private static OperatorRbacResult decideRevoke(
            OperatorRbacCommand command,
            OperatorRbacState state,
            Instant now,
            Evaluation evaluation) {
        OperatorRbacState.Assignment assignment = state.selectedAssignment();
        if (assignment == null
                || !assignment.id().equals(command.assignmentId())
                || !assignment.operatorId().equals(command.targetOperatorId())) {
            return rejected("ASSIGNMENT_NOT_FOUND", evaluation.evidence());
        }
        if (!assignment.effectiveAt(now, state.catalog())) {
            return rejected("ASSIGNMENT_NOT_ACTIVE", evaluation.evidence());
        }
        OperatorRbacState.Role targetRole = state.catalog().roles().get(assignment.roleId());
        if (targetRole == null || !targetRole.active()) {
            return rejected("ROLE_NOT_AVAILABLE", evaluation.evidence());
        }
        Evaluation withTarget = evaluation.withTargetRole(targetRole);
        String denial = delegationDenial(withTarget, targetRole);
        if (denial != null) {
            return rejected(denial, withTarget.evidence());
        }
        var mutation = new OperatorRbacDecision.Mutation(
                OperatorRbacDecision.Mutation.Kind.REVOKE,
                command.targetOperatorId(),
                assignment.roleId(),
                assignment.id(),
                state.catalog().version(),
                null,
                null,
                now,
                command.requestContext().operatorId(),
                command.reasonCode());
        return new OperatorRbacResult(
                OperatorRbacResult.DecisionStatus.APPLIED,
                "ROLE_REVOKED",
                mutation,
                withTarget.evidence());
    }

    private static Evaluation evaluateActor(
            OperatorRbacCommand command, OperatorRbacState state, Instant now) {
        OperatorRequestContext context = command.requestContext();
        if (!context.trustedExternalSubject()) {
            return Evaluation.rejected("OPERATOR_AUTHENTICATION_REQUIRED", context, false);
        }
        if (state.actor() == null || !state.actor().id().equals(context.operatorId()) || !state.actor().active()) {
            return Evaluation.rejected("OPERATOR_NOT_ACTIVE", context, false);
        }
        if (!state.actor().mfaEnrolled() || !context.mfaCompleted()) {
            return Evaluation.rejected("OPERATOR_MFA_REQUIRED", context, false);
        }
        if (state.catalog() == null || state.catalog().status() != OperatorRbacState.Status.ACTIVE) {
            return Evaluation.rejected("RBAC_CATALOG_UNAVAILABLE", context, true);
        }
        if (!state.catalog().version().equals(command.expectedCatalogVersion())) {
            return Evaluation.rejected("RBAC_CATALOG_STALE", context, true);
        }
        if (!state.catalog().permissions().contains(command.requiredPermissionId())) {
            return Evaluation.rejected("RBAC_PERMISSION_CONFIGURATION_INVALID", context, true);
        }
        boolean validSnapshot = state.catalog().roles().entrySet().stream().allMatch(entry ->
                entry.getKey().equals(entry.getValue().id())
                        && state.catalog().permissions().containsAll(entry.getValue().permissions()));
        if (!validSnapshot) {
            return Evaluation.rejected("RBAC_CATALOG_INVALID", context, true);
        }

        Set<UUID> roles = new HashSet<>();
        Set<UUID> permissions = new HashSet<>();
        Set<UUID> delegable = new HashSet<>();
        int highestRank = Integer.MIN_VALUE;
        for (OperatorRbacState.Assignment assignment : state.actorAssignments()) {
            if (!assignment.operatorId().equals(context.operatorId())
                    || !assignment.effectiveAt(now, state.catalog())) {
                continue;
            }
            OperatorRbacState.Role role = state.catalog().roles().get(assignment.roleId());
            if (role == null || !role.active()) {
                continue;
            }
            roles.add(role.id());
            permissions.addAll(role.permissions());
            delegable.addAll(role.delegablePermissions());
            highestRank = Math.max(highestRank, role.hierarchyRank());
        }
        Evaluation evaluation = new Evaluation(
                null, context, state.catalog().version(), roles, permissions, delegable, Set.of(), highestRank, true, false);
        if (!permissions.contains(command.requiredPermissionId())) {
            return evaluation.reject("RBAC_PERMISSION_DENIED");
        }
        return evaluation;
    }

    private static String delegationDenial(Evaluation evaluation, OperatorRbacState.Role targetRole) {
        if (evaluation.highestRank <= targetRole.hierarchyRank()) {
            return "ROLE_HIERARCHY_VIOLATION";
        }
        if (!evaluation.permissions.containsAll(targetRole.permissions())) {
            return "PERMISSION_ESCALATION_REJECTED";
        }
        if (!evaluation.delegable.containsAll(targetRole.permissions())) {
            return "PERMISSION_NOT_DELEGABLE";
        }
        return null;
    }

    private static OperatorRbacResult rejected(String code, OperatorRbacDecision.Evidence evidence) {
        return new OperatorRbacResult(OperatorRbacResult.DecisionStatus.REJECTED, code, null, evidence);
    }

    private record Evaluation(
            String rejectionCode,
            OperatorRequestContext context,
            String catalogVersion,
            Set<UUID> roles,
            Set<UUID> permissions,
            Set<UUID> delegable,
            Set<UUID> targetPermissions,
            int highestRank,
            boolean mfaSatisfied,
            boolean strictHierarchySatisfied) {
        private Evaluation {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
            delegable = Set.copyOf(delegable);
            targetPermissions = Set.copyOf(targetPermissions);
        }

        static Evaluation rejected(String code, OperatorRequestContext context, boolean mfaSatisfied) {
            return new Evaluation(
                    code, context, null, Set.of(), Set.of(), Set.of(), Set.of(),
                    Integer.MIN_VALUE, mfaSatisfied, false);
        }

        Evaluation reject(String code) {
            return new Evaluation(code, context, catalogVersion, roles, permissions, delegable,
                    targetPermissions, highestRank, mfaSatisfied, strictHierarchySatisfied);
        }

        Evaluation withTargetRole(OperatorRbacState.Role role) {
            return new Evaluation(rejectionCode, context, catalogVersion, roles, permissions, delegable,
                    role.permissions(), highestRank, mfaSatisfied, highestRank > role.hierarchyRank());
        }

        OperatorRbacDecision.Evidence evidence() {
            return new OperatorRbacDecision.Evidence(
                    catalogVersion,
                    roles,
                    permissions,
                    delegable,
                    targetPermissions,
                    context.trustedExternalSubject(),
                    mfaSatisfied,
                    strictHierarchySatisfied);
        }
    }
}
