package com.idea2strategy.backend.application.usercase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserCaseServiceTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000019");
    private static final UUID CASE = UUID.fromString("20000000-0000-4000-8000-000000000019");
    private static final UUID EVIDENCE = UUID.fromString("30000000-0000-4000-8000-000000000019");
    private static final UUID SOURCE = UUID.fromString("40000000-0000-4000-8000-000000000019");
    private static final Instant NOW = Instant.parse("2026-08-02T14:00:00Z");

    @Test
    void returnsTheOriginalViewForAnIdempotentReplay() {
        var expected = view(UserCaseStatus.OPEN, 1);
        var store = new StubStore(new UserCaseStore.CommandResult(
                UserCaseStore.CommandResult.Outcome.REPLAYED, expected));

        assertThat(service(store).submit(command())).isEqualTo(expected);
    }

    @Test
    void rejectsReusedKeysWithDifferentPayloads() {
        var store = new StubStore(new UserCaseStore.CommandResult(
                UserCaseStore.CommandResult.Outcome.IDEMPOTENCY_CONFLICT, null));

        assertThatThrownBy(() -> service(store).submit(command()))
                .isInstanceOf(UserCaseRejectedException.class)
                .extracting(error -> ((UserCaseRejectedException) error).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void doesNotRevealWhetherAnotherAccountsCaseExists() {
        var store = new StubStore(null);

        assertThatThrownBy(() -> service(store).detail(ACCOUNT, CASE))
                .isInstanceOf(UserCaseRejectedException.class)
                .extracting(error -> ((UserCaseRejectedException) error).code())
                .isEqualTo("RESOURCE_NOT_AVAILABLE");
    }

    @Test
    void leavesEvidenceOwnershipAndAppendOnlyTransitionAtomicityAtTheStoreBoundary() {
        var expected = view(UserCaseStatus.OPEN, 3);
        var store = new StubStore(new UserCaseStore.CommandResult(
                UserCaseStore.CommandResult.Outcome.APPLIED, expected));
        var supplement = new UserCaseSupplementCommand(
                ACCOUNT, CASE, 2, List.of(evidence()), "supplement-1", "hash-2", UUID.randomUUID());

        assertThat(service(store).supplement(supplement)).isEqualTo(expected);
        assertThat(store.lastSupplement).isEqualTo(supplement);
        assertThat(store.observedAt).isEqualTo(NOW);
    }

    private UserCaseService service(StubStore store) {
        return new UserCaseService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UserCaseCommand command() {
        return new UserCaseCommand(
                ACCOUNT, UserCaseType.APPEAL, "Appeal", "Evidence attached", List.of(evidence()),
                "submit-1", "hash-1", UUID.randomUUID());
    }

    private UserCaseEvidenceReference evidence() {
        return new UserCaseEvidenceReference(EVIDENCE, "BACKTEST_RUN", SOURCE);
    }

    private UserCaseView view(UserCaseStatus status, long version) {
        return new UserCaseView(CASE, ACCOUNT, UserCaseType.APPEAL, status, version, List.of(EVIDENCE), NOW);
    }

    private static final class StubStore implements UserCaseStore {
        private final CommandResult commandResult;
        private UserCaseSupplementCommand lastSupplement;
        private Instant observedAt;

        private StubStore(CommandResult commandResult) {
            this.commandResult = commandResult;
        }

        @Override
        public CommandResult submit(UserCaseCommand command, Instant now) {
            observedAt = now;
            return commandResult;
        }

        @Override
        public CommandResult supplement(UserCaseSupplementCommand command, Instant now) {
            lastSupplement = command;
            observedAt = now;
            return commandResult;
        }

        @Override
        public Optional<UserCaseView> findOwned(UUID accountId, UUID caseId) {
            return Optional.empty();
        }
    }
}
