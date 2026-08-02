package com.idea2strategy.backend.application.accountclosure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AccountClosureCoordinatorTest {
    private static final UUID ACCOUNT = UUID.fromString("a1200000-0000-4000-8000-000000000131");
    private static final Instant DEADLINE = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void closesExactlyOnceOnlyAfterEveryRequiredDomainIsReady() {
        var store = new RecordingStore(new AccountClosureCandidate(ACCOUNT, DEADLINE, 2));
        var probes = readyProbes();
        var coordinator = new AccountClosureCoordinator(
                store, probes, store, Clock.fixed(DEADLINE, ZoneOffset.UTC), Duration.ofHours(1));

        var first = coordinator.run(10);
        var retry = coordinator.run(10);

        assertThat(first.closed()).isEqualTo(1);
        assertThat(retry.closed()).isZero();
        assertThat(store.closeCalls).isEqualTo(1);
        assertThat(store.recorded.keySet()).containsExactlyInAnyOrderElementsOf(List.of(ClosureDomain.values()));
    }

    @Test
    void freezesBeforeDeadlineButCannotCloseEarly() {
        var store = new RecordingStore(new AccountClosureCandidate(ACCOUNT, DEADLINE, 2));
        var coordinator = new AccountClosureCoordinator(
                store,
                readyProbes(),
                store,
                Clock.fixed(DEADLINE.minusSeconds(1), ZoneOffset.UTC),
                Duration.ofHours(1));

        var result = coordinator.run(10);

        assertThat(result.closed()).isZero();
        assertThat(store.closeCalls).isZero();
        assertThat(store.recorded).hasSize(ClosureDomain.values().length);
    }

    @Test
    void dependencyFailureStaysClosingAndRaisesOneDurableAlertAfterTimeout() {
        var store = new RecordingStore(new AccountClosureCandidate(ACCOUNT, DEADLINE, 2));
        var probes = new ArrayList<AccountClosureReadinessProbe>(readyProbes());
        probes.removeIf(probe -> probe.domain() == ClosureDomain.TRADING);
        probes.add(new AccountClosureReadinessProbe() {
            public ClosureDomain domain() { return ClosureDomain.TRADING; }
            public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
                throw new IllegalStateException("trading unavailable");
            }
        });
        var coordinator = new AccountClosureCoordinator(
                store,
                probes,
                store,
                Clock.fixed(DEADLINE.plus(Duration.ofHours(2)), ZoneOffset.UTC),
                Duration.ofHours(1));

        var result = coordinator.run(10);

        assertThat(result.closed()).isZero();
        assertThat(result.blocked()).isEqualTo(1);
        assertThat(store.recorded.get(ClosureDomain.TRADING).status()).isEqualTo(ClosureReadinessStatus.BLOCKED);
        assertThat(store.alerts).containsExactly("ACCOUNT_CLOSURE_TIMEOUT");
        assertThat(store.closeCalls).isZero();
    }

    @Test
    void rejectsSemanticallyWrongReadinessMappings() {
        var store = new RecordingStore(new AccountClosureCandidate(ACCOUNT, DEADLINE, 2));
        var probes = new ArrayList<AccountClosureReadinessProbe>(readyProbes());
        probes.removeIf(probe -> probe.domain() == ClosureDomain.TRADING);
        probes.add(new AccountClosureReadinessProbe() {
            public ClosureDomain domain() { return ClosureDomain.TRADING; }
            public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
                return new ClosureReadiness(domain(), ClosureReadinessStatus.FROZEN, "WRONG_MAPPING", "{}", observedAt);
            }
        });

        var result = new AccountClosureCoordinator(store, probes, store,
                Clock.fixed(DEADLINE, ZoneOffset.UTC), Duration.ofHours(1)).run(10);

        assertThat(result.closed()).isZero();
        assertThat(store.closeCalls).isZero();
    }

    @Test
    void concurrentCoordinatorsCanCommitClosedOnlyOnce() throws Exception {
        var candidate = new AccountClosureCandidate(ACCOUNT, DEADLINE, 2);
        var closed = new AtomicBoolean();
        AccountClosureStore store = new AccountClosureStore() {
            public List<AccountClosureCandidate> findClosingCandidates(int limit) { return List.of(candidate); }
            public long beginAttempt(AccountClosureCandidate ignored, UUID correlationId, Instant startedAt) { return 1; }
            public void recordReadiness(UUID accountId, UUID correlationId, long generation,
                                        ClosureReadiness readiness) {}
            public boolean closeIfReady(AccountClosureCandidate ignored, UUID correlationId, long generation,
                                        String key, Instant at) {
                return closed.compareAndSet(false, true);
            }
        };
        var coordinator = new AccountClosureCoordinator(store, readyProbes(),
                (accountId, correlationId, code, evidence, occurredAt) -> {},
                Clock.fixed(DEADLINE, ZoneOffset.UTC), Duration.ofHours(1));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.<java.util.concurrent.Callable<AccountClosureRunResult>>of(
                    () -> coordinator.run(1), () -> coordinator.run(1));
            var results = executor.invokeAll(calls);
            assertThat(results.get(0).get().closed() + results.get(1).get().closed()).isOne();
        }
    }

    @Test
    void duplicateDomainProbeConfigurationIsRejected() {
        var probes = new ArrayList<>(readyProbes());
        probes.add(probes.getFirst());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AccountClosureCoordinator(
                        new RecordingStore(new AccountClosureCandidate(ACCOUNT, DEADLINE, 2)),
                        probes,
                        (accountId, correlationId, code, evidence, occurredAt) -> {},
                        Clock.fixed(DEADLINE, ZoneOffset.UTC), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneBrokenAccountDoesNotStarveTheRestOfTheBatch() {
        UUID broken = UUID.fromString("a1200000-0000-4000-8000-000000000001");
        UUID healthy = UUID.fromString("a1200000-0000-4000-8000-000000000002");
        var candidates = List.of(
                new AccountClosureCandidate(broken, DEADLINE, 2),
                new AccountClosureCandidate(healthy, DEADLINE, 2));
        var closed = new ArrayList<UUID>();
        var alertCodes = new ArrayList<String>();
        AccountClosureStore store = new AccountClosureStore() {
            public List<AccountClosureCandidate> findClosingCandidates(int limit) { return candidates; }
            public long beginAttempt(AccountClosureCandidate candidate, UUID correlationId, Instant startedAt) {
                if (candidate.accountId().equals(broken)) throw new IllegalStateException("broken row");
                return 1;
            }
            public void recordReadiness(UUID accountId, UUID correlationId, long generation,
                                        ClosureReadiness readiness) {}
            public boolean closeIfReady(AccountClosureCandidate candidate, UUID correlationId, long generation,
                                        String key, Instant at) {
                closed.add(candidate.accountId());
                return true;
            }
        };
        var result = new AccountClosureCoordinator(store, readyProbes(),
                (accountId, correlationId, code, evidence, occurredAt) -> alertCodes.add(code),
                Clock.fixed(DEADLINE, ZoneOffset.UTC), Duration.ofHours(1)).run(10);

        assertThat(result.inspected()).isEqualTo(2);
        assertThat(result.closed()).isOne();
        assertThat(result.blocked()).isOne();
        assertThat(closed).containsExactly(healthy);
        assertThat(alertCodes).containsExactly("ACCOUNT_CLOSURE_PROCESSING_FAILED");
    }

    private static List<AccountClosureReadinessProbe> readyProbes() {
        return java.util.Arrays.stream(ClosureDomain.values())
                .<AccountClosureReadinessProbe>map(domain -> new AccountClosureReadinessProbe() {
                    public ClosureDomain domain() { return domain; }
                    public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
                        var status = domain == ClosureDomain.TRADING
                                ? ClosureReadinessStatus.SETTLED
                                : ClosureReadinessStatus.FROZEN;
                        return new ClosureReadiness(domain, status, "READY", "{}", observedAt);
                    }
                }).toList();
    }

    private static final class RecordingStore implements AccountClosureStore, AccountClosureAlertPort {
        private final List<AccountClosureCandidate> candidates = new ArrayList<>();
        private final Map<ClosureDomain, ClosureReadiness> recorded = new EnumMap<>(ClosureDomain.class);
        private final List<String> alerts = new ArrayList<>();
        private int closeCalls;

        private RecordingStore(AccountClosureCandidate candidate) { candidates.add(candidate); }

        public List<AccountClosureCandidate> findClosingCandidates(int limit) { return List.copyOf(candidates); }
        public long beginAttempt(AccountClosureCandidate candidate, UUID correlationId, Instant startedAt) {
            return 1;
        }
        public void recordReadiness(UUID accountId, UUID correlationId, long generation, ClosureReadiness readiness) {
            recorded.put(readiness.domain(), readiness);
        }
        public boolean closeIfReady(AccountClosureCandidate candidate, UUID correlationId, long generation,
                                    String idempotencyKey, Instant closedAt) {
            closeCalls++;
            candidates.clear();
            return true;
        }
        public void raise(UUID accountId, UUID correlationId, String code, String evidence, Instant occurredAt) {
            if (!alerts.contains(code)) alerts.add(code);
        }
    }
}
