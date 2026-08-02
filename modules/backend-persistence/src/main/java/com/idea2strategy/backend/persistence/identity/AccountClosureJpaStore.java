package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.accountclosure.AccountClosureAlertPort;
import com.idea2strategy.backend.application.accountclosure.AccountClosureCandidate;
import com.idea2strategy.backend.application.accountclosure.AccountClosureStore;
import com.idea2strategy.backend.application.accountclosure.ClosureDomain;
import com.idea2strategy.backend.application.accountclosure.ClosureReadiness;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandType;
import com.idea2strategy.backend.application.identity.AccountLifecycleMutation;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountClosureJpaStore implements AccountClosureStore, AccountClosureAlertPort {
    private final EntityManager entityManager;
    private final AccountLifecycleCommandPort lifecycleCommands;

    public AccountClosureJpaStore(EntityManager entityManager, AccountLifecycleCommandPort lifecycleCommands) {
        this.entityManager = entityManager;
        this.lifecycleCommands = lifecycleCommands;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountClosureCandidate> findClosingCandidates(int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, cancellation_deadline_at, lifecycle_version
                        from identity.accounts
                        where lifecycle_status = 'CLOSING'
                        order by cancellation_deadline_at, id
                        limit :limit
                        """)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream()
                .map(row -> new AccountClosureCandidate(
                        (UUID) row[0], instant(row[1]), ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    @Transactional
    public long beginAttempt(AccountClosureCandidate candidate, UUID correlationId, Instant startedAt) {
        int inserted = entityManager.createNativeQuery("""
                        insert into identity.account_closure_runs
                            (correlation_id, account_id, lifecycle_version, cancellation_deadline_at,
                             generation, started_at, last_checked_at)
                        select :correlationId, id, lifecycle_version, cancellation_deadline_at, 1,
                               :startedAt, :startedAt
                        from identity.accounts
                        where id = :accountId and lifecycle_status = 'CLOSING'
                          and lifecycle_version = :lifecycleVersion
                        on conflict (correlation_id) do update
                        set generation = identity.account_closure_runs.generation + 1,
                            started_at = excluded.started_at,
                            last_checked_at = excluded.last_checked_at
                        where identity.account_closure_runs.account_id = excluded.account_id
                          and identity.account_closure_runs.lifecycle_version = excluded.lifecycle_version
                        """)
                .setParameter("correlationId", correlationId)
                .setParameter("accountId", candidate.accountId())
                .setParameter("lifecycleVersion", candidate.lifecycleVersion())
                .setParameter("startedAt", utc(startedAt))
                .executeUpdate();
        if (inserted != 1) {
            throw new IllegalStateException("Account is no longer an eligible CLOSING generation");
        }
        return ((Number) entityManager.createNativeQuery("""
                        select generation from identity.account_closure_runs
                        where correlation_id = :correlationId
                        """)
                .setParameter("correlationId", correlationId)
                .getSingleResult()).longValue();
    }

    @Override
    @Transactional
    public void recordReadiness(
            UUID accountId, UUID correlationId, long generation, ClosureReadiness readiness) {
        int recorded = entityManager.createNativeQuery("""
                        insert into identity.account_closure_readiness
                            (correlation_id, generation, account_id, domain, status, reason_code, evidence, observed_at)
                        select :correlationId, :generation, :accountId,
                                cast(:domain as identity.account_closure_domain),
                                cast(:status as identity.account_closure_readiness_status),
                                :reasonCode, cast(:evidence as jsonb), :observedAt
                        from identity.account_closure_runs run
                        where run.correlation_id = :correlationId and run.generation = :generation
                          and run.account_id = :accountId
                        on conflict (correlation_id, generation, domain) do update
                        set status = excluded.status,
                            reason_code = excluded.reason_code,
                            evidence = excluded.evidence,
                            observed_at = excluded.observed_at
                        """)
                .setParameter("correlationId", correlationId)
                .setParameter("generation", generation)
                .setParameter("accountId", accountId)
                .setParameter("domain", readiness.domain().name())
                .setParameter("status", readiness.status().name())
                .setParameter("reasonCode", readiness.reasonCode())
                .setParameter("evidence", readiness.evidence())
                .setParameter("observedAt", utc(readiness.observedAt()))
                .executeUpdate();
        if (recorded != 1) {
            throw new IllegalStateException("Stale account closure readiness generation");
        }
    }

    @Override
    @Transactional
    public boolean closeIfReady(
            AccountClosureCandidate candidate,
            UUID correlationId,
            long generation,
            String idempotencyKey,
            Instant closedAt) {
        Object[] account;
        try {
            account = (Object[]) entityManager.createNativeQuery("""
                            select lifecycle_version, last_lifecycle_event_id, cancellation_deadline_at
                            from identity.accounts
                            where id = :accountId and lifecycle_status = 'CLOSING'
                            for update
                            """)
                    .setParameter("accountId", candidate.accountId())
                    .getSingleResult();
        } catch (NoResultException ignored) {
            return false;
        }
        long version = ((Number) account[0]).longValue();
        Instant deadline = instant(account[2]);
        if (version != candidate.lifecycleVersion() || closedAt.isBefore(deadline)) {
            return false;
        }
        Number readyCount = (Number) entityManager.createNativeQuery("""
                        select count(*)
                        from identity.account_closure_readiness
                        where correlation_id = :correlationId
                          and generation = :generation
                          and account_id = :accountId
                          and observed_at >= (
                              select started_at from identity.account_closure_runs
                              where correlation_id = :correlationId and generation = :generation)
                          and ((domain = 'TRADING' and status = 'SETTLED')
                               or (domain <> 'TRADING' and status = 'FROZEN'))
                        """)
                .setParameter("correlationId", correlationId)
                .setParameter("generation", generation)
                .setParameter("accountId", candidate.accountId())
                .getSingleResult();
        if (readyCount.intValue() != ClosureDomain.values().length) {
            return false;
        }

        var closedAtUtc = utc(closedAt);
        var result = lifecycleCommands.executeAtomically(
                candidate.accountId(),
                AccountLifecycleCommandType.CLOSE,
                idempotencyKey,
                "closure-request:" + candidate.accountId() + ":" + candidate.lifecycleVersion()
                        + ":" + candidate.cancellationDeadlineAt(),
                correlationId,
                snapshot -> {
                    if (snapshot.status() != AccountLifecycleStatus.CLOSING
                            || snapshot.version() != candidate.lifecycleVersion()
                            || !candidate.cancellationDeadlineAt().equals(snapshot.cancellationDeadlineAt())
                            || closedAt.isBefore(snapshot.cancellationDeadlineAt())) {
                        throw new IllegalStateException("Account is no longer eligible for CLOSED");
                    }
                    return java.util.Optional.of(new AccountLifecycleMutation(
                            AccountLifecycleStatus.CLOSED,
                            closedAt,
                            snapshot.closingPreviousStatus(),
                            snapshot.withdrawalRequestedAt(),
                            snapshot.cancellationDeadlineAt(),
                            "WITHDRAWAL_COMPLETED"));
                });
        if (result.status() != AccountLifecycleStatus.CLOSED) {
            return false;
        }
        entityManager.createNativeQuery("""
                        update identity.account_closure_runs set closed_at = :closedAt
                        where correlation_id = :correlationId and generation = :generation
                        """)
                .setParameter("closedAt", closedAtUtc)
                .setParameter("correlationId", correlationId)
                .setParameter("generation", generation)
                .executeUpdate();
        return true;
    }

    @Override
    @Transactional
    public void raise(
            UUID accountId, UUID correlationId, String code, String evidence, Instant occurredAt) {
        entityManager.createNativeQuery("""
                        insert into operations.outbox_messages
                            (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                             event_schema_version, payload_document, idempotency_key, created_at)
                        select gen_random_uuid(), 'identity', :accountId, lifecycle_version,
                               'ACCOUNT_CLOSURE_ATTENTION_REQUIRED', 'account-closure.v1',
                               jsonb_build_object('accountId', cast(:accountId as text),
                                   'correlationId', cast(:correlationId as text),
                                   'code', :code, 'evidence', :evidence),
                               'account-closure-alert:' || cast(:correlationId as text) || ':' || :code,
                               :occurredAt
                        from identity.accounts where id = :accountId
                        on conflict (idempotency_key) do nothing
                        """)
                .setParameter("accountId", accountId)
                .setParameter("correlationId", correlationId)
                .setParameter("code", code)
                .setParameter("evidence", evidence)
                .setParameter("occurredAt", utc(occurredAt))
                .executeUpdate();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }
}
