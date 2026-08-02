package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AccountLifecycleService {
    private static final Duration WITHDRAWAL_CANCELLATION_WINDOW = Duration.ofDays(30);
    private static final Duration STEP_UP_WINDOW = Duration.ofMinutes(10);

    private final AccountLifecycleCommandPort commands;
    private final AccountLifecycleCandidateQueryPort candidates;
    private final Clock clock;

    public AccountLifecycleService(
            AccountLifecycleCommandPort commands,
            AccountLifecycleCandidateQueryPort candidates,
            Clock clock) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AccountLifecycleResult requestWithdrawal(AccountLifecycleCommand command) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        return commands.executeAtomically(
                command.accountId(),
                AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                command.idempotencyKey(),
                command.requestHash(),
                command.correlationId(),
                current -> {
                    requireStepUp(command.proof(), now);
                    return requestWithdrawal(current, now);
                });
    }

    public AccountLifecycleResult cancelWithdrawal(AccountLifecycleCommand command) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        return commands.executeAtomically(
                command.accountId(),
                AccountLifecycleCommandType.CANCEL_WITHDRAWAL,
                command.idempotencyKey(),
                command.requestHash(),
                command.correlationId(),
                current -> {
                    requireStepUp(command.proof(), now);
                    return cancelWithdrawal(current, now);
                });
    }

    public List<AccountLifecycleResult> markDormantCandidates(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Instant evaluatedAt = clock.instant();
        List<AccountLifecycleResult> applied = new ArrayList<>();
        Instant candidateCutoff = evaluatedAt.atOffset(ZoneOffset.UTC).minusMonths(12).plusDays(3).toInstant();
        for (AccountLifecycleSnapshot candidate : candidates.findActiveDormancyCandidates(candidateCutoff, limit)) {
            AccountLifecycleResult result = commands.executeAtomically(
                    candidate.accountId(),
                    AccountLifecycleCommandType.MARK_DORMANT,
                    "dormancy:" + evaluatedAt,
                    "dormancy:" + evaluatedAt,
                    candidate.accountId(),
                    current -> markDormant(current, evaluatedAt));
            if (result.applied()) {
                applied.add(result);
            }
        }
        return List.copyOf(applied);
    }

    private static Optional<AccountLifecycleMutation> requestWithdrawal(
            AccountLifecycleSnapshot current, Instant now) {
        rejectClosed(current);
        if (current.status() == AccountLifecycleStatus.CLOSING) {
            return Optional.empty();
        }
        if (current.status() != AccountLifecycleStatus.ACTIVE
                && current.status() != AccountLifecycleStatus.DORMANT) {
            throw rejected("INVALID_LIFECYCLE_TRANSITION");
        }
        return Optional.of(new AccountLifecycleMutation(
                AccountLifecycleStatus.CLOSING,
                now,
                current.status(),
                now,
                now.plus(WITHDRAWAL_CANCELLATION_WINDOW),
                "WITHDRAWAL_REQUESTED"));
    }

    private static Optional<AccountLifecycleMutation> cancelWithdrawal(
            AccountLifecycleSnapshot current, Instant now) {
        rejectClosed(current);
        if (current.status() != AccountLifecycleStatus.CLOSING) {
            throw rejected("WITHDRAWAL_NOT_PENDING");
        }
        if (current.cancellationDeadlineAt() == null || !now.isBefore(current.cancellationDeadlineAt())) {
            throw rejected("WITHDRAWAL_CANCELLATION_EXPIRED");
        }
        AccountLifecycleStatus restored = current.closingPreviousStatus();
        if (restored != AccountLifecycleStatus.ACTIVE && restored != AccountLifecycleStatus.DORMANT) {
            throw rejected("INVALID_LIFECYCLE_TRANSITION");
        }
        return Optional.of(new AccountLifecycleMutation(
                restored, now, null, null, null, "WITHDRAWAL_CANCELLED"));
    }

    private static Optional<AccountLifecycleMutation> markDormant(
            AccountLifecycleSnapshot current, Instant evaluatedAt) {
        if (current.status() != AccountLifecycleStatus.ACTIVE || current.lastSuccessfulAuthAt() == null) {
            return Optional.empty();
        }
        Instant eligibleAt = current.lastSuccessfulAuthAt()
                .atOffset(ZoneOffset.UTC)
                .plusMonths(12)
                .toInstant();
        if (eligibleAt.isAfter(evaluatedAt)) {
            return Optional.empty();
        }
        return Optional.of(new AccountLifecycleMutation(
                AccountLifecycleStatus.DORMANT,
                evaluatedAt,
                null,
                null,
                null,
                "DORMANCY_THRESHOLD_REACHED"));
    }

    private static void requireStepUp(AccountLifecycleAuthenticationProof proof, Instant now) {
        boolean acceptedMethod = proof.method() == AccountLifecycleAuthenticationMethod.PASSWORD
                || proof.method() == AccountLifecycleAuthenticationMethod.OIDC;
        Duration age = Duration.between(proof.authenticatedAt(), now);
        if (!proof.active()
                || !acceptedMethod
                || age.isNegative()
                || age.compareTo(STEP_UP_WINDOW) > 0) {
            throw rejected("STEP_UP_REQUIRED");
        }
    }

    private static void rejectClosed(AccountLifecycleSnapshot current) {
        if (current.status() == AccountLifecycleStatus.CLOSED) {
            throw rejected("ACCOUNT_CLOSED");
        }
    }

    private static AccountLifecycleRejectedException rejected(String code) {
        return new AccountLifecycleRejectedException(code);
    }
}
