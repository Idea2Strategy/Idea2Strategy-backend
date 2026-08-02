package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountReactivationServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("13000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("13000000-0000-4000-8000-000000000002");
    private static final UUID CORRELATION_ID = UUID.fromString("13000000-0000-4000-8000-000000000003");
    private static final UUID CHALLENGE_ID = UUID.fromString("13000000-0000-4000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");

    @Test
    void reactivatesOnlyDormantAccountsAfterCurrentEligibilityIsConfirmed() {
        var repository = new Repository(dormant());
        var eligibility = new Eligibility(AccountReactivationEligibility.allowed());
        var service = new AccountLifecycleService(repository, repository, eligibility, fixedClock());

        AccountLifecycleResult result = service.reactivate(command(oidcProof(ACCOUNT_ID, NOW.minusSeconds(600), NOW)));

        assertThat(result.status()).isEqualTo(AccountLifecycleStatus.ACTIVE);
        assertThat(result.version()).isEqualTo(3);
        assertThat(repository.commandType).isEqualTo(AccountLifecycleCommandType.REACTIVATE);
        assertThat(repository.mutation.reasonCode()).isEqualTo("ACCOUNT_REACTIVATED");
        assertThat(eligibility.accountId).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void rejectsMismatchedAccountsAndUsesTheOlderAuthenticationTimestampForTenMinutes() {
        var repository = new Repository(dormant());
        var service = new AccountLifecycleService(
                repository, repository, new Eligibility(AccountReactivationEligibility.allowed()), fixedClock());

        assertThatThrownBy(() -> service.reactivate(command(oidcProof(OTHER_ACCOUNT_ID, NOW, NOW))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("STEP_UP_REQUIRED");
        assertThatThrownBy(() -> service.reactivate(command(oidcProof(
                        ACCOUNT_ID, NOW.minusSeconds(601), NOW.minusSeconds(1)))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("STEP_UP_REQUIRED");
    }

    @Test
    void leavesDormantWhenConsentOrEffectiveRestrictionBlocksReactivation() {
        for (String reason : List.of("CURRENT_REQUIRED_CONSENT_MISSING", "ACCOUNT_REACTIVATION_RESTRICTED")) {
            var repository = new Repository(dormant());
            var service = new AccountLifecycleService(
                    repository,
                    repository,
                    new Eligibility(AccountReactivationEligibility.rejected(reason)),
                    fixedClock());

            assertThatThrownBy(() -> service.reactivate(command(oidcProof(ACCOUNT_ID, NOW, NOW))))
                    .isInstanceOf(AccountLifecycleRejectedException.class)
                    .hasMessage(reason);
            assertThat(repository.current.status()).isEqualTo(AccountLifecycleStatus.DORMANT);
            assertThat(repository.mutation).isNull();
        }
    }

    @Test
    void rejectsRecoveryCodesAndEveryStateOtherThanDormant() {
        var active = new AccountLifecycleSnapshot(
                ACCOUNT_ID, AccountLifecycleStatus.ACTIVE, 2, NOW, null, null, null);
        var repository = new Repository(active);
        var service = new AccountLifecycleService(
                repository, repository, new Eligibility(AccountReactivationEligibility.allowed()), fixedClock());
        var recovery = new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.RECOVERY_CODE,
                ACCOUNT_ID,
                null,
                null,
                NOW,
                NOW,
                true);

        assertThatThrownBy(() -> service.reactivate(command(recovery)))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("STEP_UP_REQUIRED");
        assertThatThrownBy(() -> service.reactivate(command(oidcProof(ACCOUNT_ID, NOW, NOW))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("REACTIVATION_NOT_DORMANT");
    }

    private static AccountLifecycleCommand command(AccountLifecycleAuthenticationProof proof) {
        return new AccountLifecycleCommand(
                ACCOUNT_ID, "reactivate-1", "reactivate-hash", CORRELATION_ID, proof);
    }

    private static AccountLifecycleAuthenticationProof oidcProof(
            UUID accountId, Instant authenticatedAt, Instant verifiedAt) {
        return new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.OIDC,
                accountId,
                "GOOGLE",
                CHALLENGE_ID,
                authenticatedAt,
                verifiedAt,
                true);
    }

    private static AccountLifecycleSnapshot dormant() {
        return new AccountLifecycleSnapshot(
                ACCOUNT_ID, AccountLifecycleStatus.DORMANT, 2, NOW.minusSeconds(100), null, null, null);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class Eligibility implements AccountReactivationEligibilityPort {
        private final AccountReactivationEligibility result;
        private UUID accountId;

        private Eligibility(AccountReactivationEligibility result) {
            this.result = result;
        }

        @Override
        public AccountReactivationEligibility evaluateAndConsume(
                UUID accountId,
                AccountLifecycleAuthenticationProof proof,
                java.util.Set<UUID> acceptedPolicyDocumentIds,
                UUID correlationId,
                Instant now) {
            this.accountId = accountId;
            return result;
        }
    }

    private static final class Repository implements AccountLifecycleCommandPort, AccountLifecycleCandidateQueryPort {
        private AccountLifecycleSnapshot current;
        private AccountLifecycleCommandType commandType;
        private AccountLifecycleMutation mutation;

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
            this.commandType = commandType;
            Optional<AccountLifecycleMutation> decided = decision.decide(current);
            this.mutation = decided.orElse(null);
            if (mutation == null) {
                return AccountLifecycleResult.skipped(current);
            }
            current = mutation.applyTo(current);
            return AccountLifecycleResult.applied(current);
        }

        @Override
        public List<AccountLifecycleSnapshot> findActiveDormancyCandidates(Instant eligibleAt, int limit) {
            return List.of();
        }
    }
}
