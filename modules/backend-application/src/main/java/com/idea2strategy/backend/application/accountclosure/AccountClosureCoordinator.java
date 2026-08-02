package com.idea2strategy.backend.application.accountclosure;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AccountClosureCoordinator {
    private static final String TIMEOUT_ALERT = "ACCOUNT_CLOSURE_TIMEOUT";

    private final AccountClosureStore store;
    private final Map<ClosureDomain, AccountClosureReadinessProbe> probes;
    private final AccountClosureAlertPort alerts;
    private final Clock clock;
    private final Duration timeout;

    public AccountClosureCoordinator(
            AccountClosureStore store,
            List<AccountClosureReadinessProbe> probes,
            AccountClosureAlertPort alerts,
            Clock clock,
            Duration timeout) {
        this.store = Objects.requireNonNull(store, "store");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        this.probes = indexProbes(probes);
    }

    public AccountClosureRunResult run(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        var now = clock.instant();
        int inspected = 0;
        int closed = 0;
        int blocked = 0;
        for (var candidate : store.findClosingCandidates(limit)) {
            inspected++;
            try {
                var outcome = process(candidate, now);
                closed += outcome.closed();
                blocked += outcome.blocked();
            } catch (RuntimeException failure) {
                blocked++;
                try {
                    alerts.raise(candidate.accountId(), correlationId(candidate),
                            "ACCOUNT_CLOSURE_PROCESSING_FAILED", "Account-scoped attempt rolled back", now);
                } catch (RuntimeException ignored) {
                    // One broken account and even a broken alert sink must not starve the remaining accounts.
                }
            }
        }
        return new AccountClosureRunResult(inspected, closed, blocked);
    }

    private CandidateOutcome process(AccountClosureCandidate candidate, Instant now) {
        var correlationId = correlationId(candidate);
        var generation = store.beginAttempt(candidate, correlationId, now);
        boolean ready = true;
        for (var domain : ClosureDomain.values()) {
            var readiness = evaluate(probes.get(domain), domain, candidate.accountId(), correlationId, now);
            store.recordReadiness(candidate.accountId(), correlationId, generation, readiness);
            ready &= readiness.status().allowsClosure(domain);
        }
        if (!ready) {
            if (!now.isBefore(candidate.cancellationDeadlineAt().plus(timeout))) {
                alerts.raise(candidate.accountId(), correlationId, TIMEOUT_ALERT,
                        "One or more closure domains are not ready", now);
            }
            return new CandidateOutcome(0, 1);
        }
        if (!now.isBefore(candidate.cancellationDeadlineAt())
                && store.closeIfReady(candidate, correlationId, generation, idempotencyKey(candidate), now)) {
            return new CandidateOutcome(1, 0);
        }
        return new CandidateOutcome(0, 0);
    }

    private static ClosureReadiness evaluate(
            AccountClosureReadinessProbe probe,
            ClosureDomain domain,
            UUID accountId,
            UUID correlationId,
            Instant now) {
        try {
            var readiness = probe.evaluate(accountId, correlationId, now);
            if (readiness == null || readiness.domain() != domain) {
                return blocked(domain, "INVALID_PROBE_RESULT", now);
            }
            return readiness;
        } catch (RuntimeException failure) {
            return blocked(domain, "DEPENDENCY_UNAVAILABLE", now);
        }
    }

    private static ClosureReadiness blocked(ClosureDomain domain, String reason, Instant now) {
        return new ClosureReadiness(domain, ClosureReadinessStatus.BLOCKED, reason, "{}", now);
    }

    private static Map<ClosureDomain, AccountClosureReadinessProbe> indexProbes(
            List<AccountClosureReadinessProbe> probes) {
        Objects.requireNonNull(probes, "probes");
        var indexed = new EnumMap<ClosureDomain, AccountClosureReadinessProbe>(ClosureDomain.class);
        var duplicates = new HashSet<ClosureDomain>();
        for (var probe : probes) {
            Objects.requireNonNull(probe, "probe");
            if (indexed.put(probe.domain(), probe) != null) {
                duplicates.add(probe.domain());
            }
        }
        if (!duplicates.isEmpty() || indexed.size() != ClosureDomain.values().length) {
            throw new IllegalArgumentException("Exactly one readiness probe is required for every closure domain");
        }
        return Map.copyOf(indexed);
    }

    private static UUID correlationId(AccountClosureCandidate candidate) {
        return UUID.nameUUIDFromBytes(("account-closure:" + candidate.accountId() + ":"
                + candidate.lifecycleVersion() + ":" + candidate.cancellationDeadlineAt())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String idempotencyKey(AccountClosureCandidate candidate) {
        return "account-closure:" + candidate.accountId() + ":" + candidate.lifecycleVersion()
                + ":" + candidate.cancellationDeadlineAt();
    }

    private record CandidateOutcome(int closed, int blocked) {}
}
