package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.MutableClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountLifecycleServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000012");
    private static final UUID CORRELATION_ID = UUID.fromString("20000000-0000-4000-8000-000000000012");
    private static final Instant NOW = Instant.parse("2026-08-02T06:00:00Z");

    @Test
    void requestsWithdrawalWithAnExactThirtyDayDeadlineAndDelegatedIdempotency() {
        var repository = new Repository(active(NOW.minusSeconds(60)));
        var service = service(repository, NOW);
        var command = command("withdraw-1", proof(AccountLifecycleAuthenticationMethod.PASSWORD, NOW.minusSeconds(599)));

        AccountLifecycleResult first = service.requestWithdrawal(command);
        AccountLifecycleResult replay = service.requestWithdrawal(command);

        assertThat(first.status()).isEqualTo(AccountLifecycleStatus.CLOSING);
        assertThat(first.withdrawalRequestedAt()).isEqualTo(NOW);
        assertThat(first.cancellationDeadlineAt()).isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
        assertThat(replay).isEqualTo(first);
        assertThat(repository.appliedMutations).hasSize(1);
    }

    @Test
    void replaysTheOriginalResultAfterTheAuthenticationProofAgesOut() {
        var repository = new Repository(active(NOW.minusSeconds(60)));
        var clock = new MutableClock(NOW, ZoneOffset.UTC);
        var service = new AccountLifecycleService(repository, repository, clock);
        var command = command("withdraw-replay", proof(AccountLifecycleAuthenticationMethod.PASSWORD, NOW));
        AccountLifecycleResult first = service.requestWithdrawal(command);
        clock.advanceTo(NOW.plusSeconds(601));

        AccountLifecycleResult replay = service.requestWithdrawal(command);

        assertThat(replay).isEqualTo(first);
        assertThat(repository.appliedMutations).hasSize(1);
    }

    @Test
    void returnsTheExistingWithdrawalWithoutExtendingItsDeadlineForANewKey() {
        var repository = new Repository(closing(AccountLifecycleStatus.ACTIVE, NOW.plusSeconds(60)));

        AccountLifecycleResult result = service(repository, NOW).requestWithdrawal(command(
                "different-request", proof(AccountLifecycleAuthenticationMethod.PASSWORD, NOW)));

        assertThat(result.applied()).isFalse();
        assertThat(result.cancellationDeadlineAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(repository.appliedMutations).isEmpty();
    }

    @Test
    void acceptsActivePasswordOrOidcProofThroughTenMinutesButRejectsRecoveryAndOlderProof() {
        for (AccountLifecycleAuthenticationMethod method : List.of(
                AccountLifecycleAuthenticationMethod.PASSWORD,
                AccountLifecycleAuthenticationMethod.OIDC)) {
            var repository = new Repository(active(NOW.minusSeconds(60)));
            service(repository, NOW).requestWithdrawal(command(method.name(), proof(method, NOW.minusSeconds(600))));
            assertThat(repository.appliedMutations).hasSize(1);
        }

        assertThatThrownBy(() -> service(new Repository(active(NOW)), NOW)
                        .requestWithdrawal(command("recovery", proof(AccountLifecycleAuthenticationMethod.RECOVERY_CODE, NOW))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessageContaining("STEP_UP_REQUIRED");
        assertThatThrownBy(() -> service(new Repository(active(NOW)), NOW)
                        .requestWithdrawal(command(
                                "expired", proof(AccountLifecycleAuthenticationMethod.PASSWORD, NOW.minusSeconds(601)))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessageContaining("STEP_UP_REQUIRED");
        assertThatThrownBy(() -> service(new Repository(active(NOW)), NOW)
                        .requestWithdrawal(command(
                                "inactive",
                                new AccountLifecycleAuthenticationProof(
                                        AccountLifecycleAuthenticationMethod.OIDC, NOW, false))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessageContaining("STEP_UP_REQUIRED");
    }

    @Test
    void cancelsStrictlyBeforeTheDeadlineAndRestoresDormant() {
        Instant deadline = NOW.plusSeconds(1);
        var repository = new Repository(closing(AccountLifecycleStatus.DORMANT, deadline));

        AccountLifecycleResult result = service(repository, NOW)
                .cancelWithdrawal(command("cancel-1", proof(AccountLifecycleAuthenticationMethod.OIDC, NOW)));

        assertThat(result.status()).isEqualTo(AccountLifecycleStatus.DORMANT);
        assertThat(result.withdrawalRequestedAt()).isNull();
        assertThat(result.cancellationDeadlineAt()).isNull();
    }

    @Test
    void rejectsCancellationAtTheExactDeadlineWithoutChangingState() {
        var repository = new Repository(closing(AccountLifecycleStatus.ACTIVE, NOW));

        assertThatThrownBy(() -> service(repository, NOW)
                        .cancelWithdrawal(command("cancel-expired", proof(AccountLifecycleAuthenticationMethod.PASSWORD, NOW))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessageContaining("WITHDRAWAL_CANCELLATION_EXPIRED");
        assertThat(repository.current.status()).isEqualTo(AccountLifecycleStatus.CLOSING);
        assertThat(repository.appliedMutations).isEmpty();
    }

    @Test
    void treatsClosedAsTerminalForUserCommands() {
        var repository = new Repository(new AccountLifecycleSnapshot(
                ACCOUNT_ID, AccountLifecycleStatus.CLOSED, 4, NOW.minusSeconds(100), null, null, null));
        var service = service(repository, NOW);

        assertThatThrownBy(() -> service.requestWithdrawal(command("closed-request", proof(
                        AccountLifecycleAuthenticationMethod.PASSWORD, NOW))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessageContaining("ACCOUNT_CLOSED");
        assertThatThrownBy(() -> service.cancelWithdrawal(command("closed-cancel", proof(
                        AccountLifecycleAuthenticationMethod.PASSWORD, NOW))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessageContaining("ACCOUNT_CLOSED");
    }

    @Test
    void marksDormantAfterTwelveUtcCalendarMonthsIncludingMonthEnd() {
        Instant janMonthEnd = Instant.parse("2025-01-31T06:00:00Z");
        Instant febMonthEnd = Instant.parse("2026-02-28T06:00:00Z");
        var repository = new Repository(new AccountLifecycleSnapshot(
                ACCOUNT_ID, AccountLifecycleStatus.ACTIVE, 3, janMonthEnd, null, null, null));
        repository.candidates = List.of(repository.current);

        List<AccountLifecycleResult> results = service(repository, febMonthEnd).markDormantCandidates(100);

        assertThat(repository.requestedEligibleAt).isEqualTo(Instant.parse("2025-03-03T06:00:00Z"));
        assertThat(results).extracting(AccountLifecycleResult::status)
                .containsExactly(AccountLifecycleStatus.DORMANT);
    }

    @Test
    void treatsLeapDayAsEligibleAtTheClampedFebruaryMonthEnd() {
        Instant leapDay = Instant.parse("2024-02-29T06:00:00Z");
        Instant clampedMonthEnd = Instant.parse("2025-02-28T06:00:00Z");
        var repository = new Repository(new AccountLifecycleSnapshot(
                ACCOUNT_ID, AccountLifecycleStatus.ACTIVE, 3, leapDay, null, null, null));
        repository.candidates = List.of(repository.current);

        List<AccountLifecycleResult> results = service(repository, clampedMonthEnd).markDormantCandidates(100);

        assertThat(results).extracting(AccountLifecycleResult::status)
                .containsExactly(AccountLifecycleStatus.DORMANT);
    }

    @Test
    void rechecksDormancyInsideTheAtomicCommandAndSkipsRecentlyAuthenticatedAccounts() {
        var repository = new Repository(active(NOW.minusSeconds(60)));
        repository.candidates = List.of(new AccountLifecycleSnapshot(
                ACCOUNT_ID, AccountLifecycleStatus.ACTIVE, 1, NOW.minusSeconds(400L * 24 * 3600), null, null, null));

        List<AccountLifecycleResult> results = service(repository, NOW).markDormantCandidates(100);

        assertThat(results).isEmpty();
        assertThat(repository.appliedMutations).isEmpty();
    }

    private static AccountLifecycleService service(Repository repository, Instant now) {
        return new AccountLifecycleService(repository, repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AccountLifecycleCommand command(String key, AccountLifecycleAuthenticationProof proof) {
        return new AccountLifecycleCommand(ACCOUNT_ID, key, "request-hash", CORRELATION_ID, proof);
    }

    private static AccountLifecycleAuthenticationProof proof(
            AccountLifecycleAuthenticationMethod method, Instant authenticatedAt) {
        return new AccountLifecycleAuthenticationProof(method, authenticatedAt, true);
    }

    private static AccountLifecycleSnapshot active(Instant lastSuccessfulAuthAt) {
        return new AccountLifecycleSnapshot(
                ACCOUNT_ID, AccountLifecycleStatus.ACTIVE, 1, lastSuccessfulAuthAt, null, null, null);
    }

    private static AccountLifecycleSnapshot closing(AccountLifecycleStatus previous, Instant deadline) {
        return new AccountLifecycleSnapshot(
                ACCOUNT_ID,
                AccountLifecycleStatus.CLOSING,
                2,
                NOW.minusSeconds(300),
                NOW.minusSeconds(100),
                deadline,
                previous);
    }

    private static final class Repository implements AccountLifecycleCommandPort, AccountLifecycleCandidateQueryPort {
        private AccountLifecycleSnapshot current;
        private List<AccountLifecycleSnapshot> candidates = List.of();
        private Instant requestedEligibleAt;
        private final List<AccountLifecycleMutation> appliedMutations = new ArrayList<>();
        private final Map<String, AccountLifecycleResult> idempotentResults = new HashMap<>();

        private Repository(AccountLifecycleSnapshot current) {
            this.current = current;
        }

        @Override
        public AccountLifecycleResult executeAtomically(
                UUID accountId,
                AccountLifecycleCommandType commandType,
                String idempotencyKey,
                String requestHash,
                UUID correlationId,
                AccountLifecycleDecision decision) {
            String scope = accountId + ":" + commandType + ":" + idempotencyKey;
            if (idempotentResults.containsKey(scope)) {
                return idempotentResults.get(scope);
            }
            Optional<AccountLifecycleMutation> mutation = decision.decide(current);
            if (mutation.isEmpty()) {
                return AccountLifecycleResult.skipped(current);
            }
            AccountLifecycleMutation applied = mutation.orElseThrow();
            appliedMutations.add(applied);
            current = applied.applyTo(current);
            AccountLifecycleResult result = AccountLifecycleResult.applied(current);
            idempotentResults.put(scope, result);
            return result;
        }

        @Override
        public List<AccountLifecycleSnapshot> findActiveDormancyCandidates(Instant eligibleAt, int limit) {
            this.requestedEligibleAt = eligibleAt;
            return candidates;
        }
    }
}
