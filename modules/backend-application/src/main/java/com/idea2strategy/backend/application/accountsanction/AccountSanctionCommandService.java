package com.idea2strategy.backend.application.accountsanction;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccountSanctionCommandService {
    private final AccountSanctionCommandPort commands;
    private final AccountSanctionAuthorizationPort authorization;
    private final AccountAccessRevocationPort accessRevocation;
    private final AccountSanctionOutboxPublicationPort outbox;
    private final UUID applyPermissionId;
    private final UUID liftPermissionId;
    private final Clock clock;

    public AccountSanctionCommandService(
            AccountSanctionCommandPort commands,
            AccountSanctionAuthorizationPort authorization,
            AccountAccessRevocationPort accessRevocation,
            AccountSanctionOutboxPublicationPort outbox,
            UUID applyPermissionId,
            UUID liftPermissionId,
            Clock clock) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.accessRevocation = Objects.requireNonNull(accessRevocation, "accessRevocation");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.applyPermissionId = Objects.requireNonNull(applyPermissionId, "applyPermissionId");
        this.liftPermissionId = Objects.requireNonNull(liftPermissionId, "liftPermissionId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AccountSanctionResult execute(AccountSanctionCommand command) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        AccountSanctionAuthorizationPort.Decision authorizationDecision = authorize(command, now);
        return commands.executeAtomically(
                command,
                now,
                authorizationDecision,
                (state, authorized) -> decide(command, state, authorized, now),
                this::publishEffects);
    }

    private AccountSanctionAuthorizationPort.Decision authorize(
            AccountSanctionCommand command, Instant evaluatedAt) {
        if (command.type() == AccountSanctionCommand.Type.EXPIRE) {
            return AccountSanctionAuthorizationPort.Decision.system();
        }
        if (!command.requestContext().trustedExternalSubject()) {
            throw new AccountSanctionAuthenticationRejectedException();
        }
        UUID requiredPermission = command.type() == AccountSanctionCommand.Type.APPLY
                ? applyPermissionId
                : liftPermissionId;
        AccountSanctionAuthorizationPort.Decision decision =
                authorization.authorize(command.requestContext(), requiredPermission, evaluatedAt);
        if (decision.authorized()
                && (!decision.activeOperator()
                        || !decision.mfaSatisfied()
                        || !decision.permissionIds().contains(requiredPermission))) {
            return new AccountSanctionAuthorizationPort.Decision(
                    false,
                    "SANCTION_AUTHORIZATION_EVIDENCE_INVALID",
                    decision.catalogVersion(),
                    decision.roleIds(),
                    decision.permissionIds(),
                    decision.activeOperator(),
                    decision.mfaSatisfied());
        }
        return decision;
    }

    private void publishEffects(AccountSanctionResult result) {
        if (result.accessRevocation() != null) {
            accessRevocation.revoke(result.accessRevocation());
        }
        if (!result.outboxMessages().isEmpty()) {
            outbox.publish(result.outboxMessages());
        }
    }

    private static AccountSanctionResult decide(
            AccountSanctionCommand command,
            AccountSanctionState state,
            AccountSanctionAuthorizationPort.Decision authorization,
            Instant now) {
        if (!state.accountId().equals(command.accountId())) {
            return rejected("SANCTION_ACCOUNT_MISMATCH", authorization);
        }
        if (!authorization.authorized()) {
            return rejected(authorization.code(), authorization);
        }
        if (state.version() != command.expectedVersion()) {
            return rejected("SANCTION_VERSION_CONFLICT", authorization);
        }
        return switch (command.type()) {
            case APPLY -> decideApply(command, state, authorization, now);
            case LIFT -> decideLift(command, state, authorization, now);
            case EXPIRE -> decideExpire(command, state, authorization, now);
        };
    }

    private static AccountSanctionResult decideApply(
            AccountSanctionCommand command,
            AccountSanctionState state,
            AccountSanctionAuthorizationPort.Decision authorization,
            Instant now) {
        if (state.find(command.sanctionId()) != null) {
            return noOp("SANCTION_ALREADY_EXISTS", authorization);
        }
        if (command.sanctionType() == AccountSanctionState.Type.SUSPENSION
                && !now.isBefore(command.expiresAt())) {
            return rejected("INVALID_SANCTION_EXPIRY", authorization);
        }
        var mutation = new AccountSanctionResult.Mutation(
                AccountSanctionResult.Mutation.Kind.APPLY,
                command.accountId(),
                command.sanctionId(),
                command.sanctionType(),
                null,
                AccountSanctionState.Status.ACTIVE,
                command.reasonCode(),
                command.reasonCode(),
                now,
                now,
                command.expiresAt(),
                command.sourceCaseId(),
                command.requestContext().operatorId(),
                command.correlationId(),
                now,
                state.version(),
                state.version() + 1);
        var access = new AccountAccessRevocationPort.Effect(
                command.accountId(), command.sanctionId(), true, true,
                "ACCOUNT_SANCTION_APPLIED", command.correlationId(), now);
        List<AccountSanctionOutboxPublicationPort.Message> messages = messages(
                command, state.version() + 1, now,
                "ACCOUNT_SANCTION_APPLIED",
                "ACCOUNT_ACCESS_BLOCKED",
                "ACCOUNT_EXECUTION_STOP_REQUESTED");
        return applied("SANCTION_APPLIED", mutation, authorization, access, messages);
    }

    private static AccountSanctionResult decideLift(
            AccountSanctionCommand command,
            AccountSanctionState state,
            AccountSanctionAuthorizationPort.Decision authorization,
            Instant now) {
        AccountSanctionState.Sanction current = state.find(command.sanctionId());
        if (current == null) {
            return rejected("SANCTION_NOT_FOUND", authorization);
        }
        if (current.status() != AccountSanctionState.Status.ACTIVE) {
            return noOp("SANCTION_NOT_ACTIVE", authorization);
        }
        if (current.type() == AccountSanctionState.Type.SUSPENSION
                && !now.isBefore(current.expiresAt())) {
            return rejected("SANCTION_EXPIRY_REQUIRED", authorization);
        }
        var mutation = transition(
                AccountSanctionResult.Mutation.Kind.LIFT,
                AccountSanctionState.Status.LIFTED,
                command,
                state,
                current,
                command.requestContext().operatorId(),
                now);
        var types = new ArrayList<String>();
        types.add("ACCOUNT_SANCTION_LIFTED");
        if (!state.hasOtherEffectiveSanction(command.sanctionId(), now)) {
            types.add("ACCOUNT_ACCESS_RESTORED");
        }
        return applied(
                "SANCTION_LIFTED", mutation, authorization, null,
                messages(command, state.version() + 1, now, types.toArray(String[]::new)));
    }

    private static AccountSanctionResult decideExpire(
            AccountSanctionCommand command,
            AccountSanctionState state,
            AccountSanctionAuthorizationPort.Decision authorization,
            Instant now) {
        AccountSanctionState.Sanction current = state.find(command.sanctionId());
        if (current == null) {
            return rejected("SANCTION_NOT_FOUND", authorization);
        }
        if (current.status() != AccountSanctionState.Status.ACTIVE) {
            return noOp("SANCTION_NOT_ACTIVE", authorization);
        }
        if (current.type() != AccountSanctionState.Type.SUSPENSION || current.expiresAt() == null) {
            return rejected("SANCTION_NOT_EXPIRABLE", authorization);
        }
        if (now.isBefore(current.expiresAt())) {
            return noOp("SANCTION_NOT_EXPIRED", authorization);
        }
        var mutation = transition(
                AccountSanctionResult.Mutation.Kind.EXPIRE,
                AccountSanctionState.Status.EXPIRED,
                command,
                state,
                current,
                null,
                now);
        var types = new ArrayList<String>();
        types.add("ACCOUNT_SANCTION_EXPIRED");
        if (!state.hasOtherEffectiveSanction(command.sanctionId(), now)) {
            types.add("ACCOUNT_ACCESS_RESTORED");
        }
        return applied(
                "SANCTION_EXPIRED", mutation, authorization, null,
                messages(command, state.version() + 1, now, types.toArray(String[]::new)));
    }

    private static AccountSanctionResult.Mutation transition(
            AccountSanctionResult.Mutation.Kind kind,
            AccountSanctionState.Status after,
            AccountSanctionCommand command,
            AccountSanctionState state,
            AccountSanctionState.Sanction current,
            UUID actorOperatorId,
            Instant now) {
        return new AccountSanctionResult.Mutation(
                kind,
                command.accountId(),
                command.sanctionId(),
                current.type(),
                current.status(),
                after,
                current.reasonCode(),
                command.reasonCode(),
                current.appliedAt(),
                current.effectiveAt(),
                current.expiresAt(),
                current.sourceCaseId(),
                actorOperatorId,
                command.correlationId(),
                now,
                state.version(),
                state.version() + 1);
    }

    private static List<AccountSanctionOutboxPublicationPort.Message> messages(
            AccountSanctionCommand command,
            long newVersion,
            Instant now,
            String... types) {
        return List.of(types).stream()
                .map(type -> new AccountSanctionOutboxPublicationPort.Message(
                        type,
                        command.accountId(),
                        command.sanctionId(),
                        command.correlationId(),
                        command.sanctionId() + ":" + type + ":" + newVersion,
                        now))
                .toList();
    }

    private static AccountSanctionResult applied(
            String code,
            AccountSanctionResult.Mutation mutation,
            AccountSanctionAuthorizationPort.Decision authorization,
            AccountAccessRevocationPort.Effect access,
            List<AccountSanctionOutboxPublicationPort.Message> messages) {
        return new AccountSanctionResult(
                AccountSanctionResult.Status.APPLIED, code, mutation, authorization, access, messages);
    }

    private static AccountSanctionResult rejected(
            String code, AccountSanctionAuthorizationPort.Decision authorization) {
        return new AccountSanctionResult(
                AccountSanctionResult.Status.REJECTED, code, null, authorization, null, List.of());
    }

    private static AccountSanctionResult noOp(
            String code, AccountSanctionAuthorizationPort.Decision authorization) {
        return new AccountSanctionResult(
                AccountSanctionResult.Status.NO_OP, code, null, authorization, null, List.of());
    }
}
