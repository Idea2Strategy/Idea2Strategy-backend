package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.accountclosure.AccountClosureAlertPort;
import com.idea2strategy.backend.application.accountclosure.AccountClosureCandidate;
import com.idea2strategy.backend.application.accountclosure.AccountClosureStore;
import com.idea2strategy.backend.application.accountclosure.ClosureDomain;
import com.idea2strategy.backend.application.accountclosure.ClosureReadiness;
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

    public AccountClosureJpaStore(EntityManager entityManager) {
        this.entityManager = entityManager;
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
    public void recordReadiness(UUID accountId, UUID correlationId, ClosureReadiness readiness) {
        entityManager.createNativeQuery("""
                        insert into identity.account_closure_runs
                            (correlation_id, account_id, lifecycle_version, cancellation_deadline_at,
                             started_at, last_checked_at)
                        select :correlationId, id, lifecycle_version, cancellation_deadline_at,
                               :observedAt, :observedAt
                        from identity.accounts
                        where id = :accountId and lifecycle_status = 'CLOSING'
                        on conflict (correlation_id) do update
                        set last_checked_at = excluded.last_checked_at
                        """)
                .setParameter("correlationId", correlationId)
                .setParameter("accountId", accountId)
                .setParameter("observedAt", utc(readiness.observedAt()))
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.account_closure_readiness
                            (correlation_id, account_id, domain, status, reason_code, evidence, observed_at)
                        values (:correlationId, :accountId,
                                cast(:domain as identity.account_closure_domain),
                                cast(:status as identity.account_closure_readiness_status),
                                :reasonCode, cast(:evidence as jsonb), :observedAt)
                        on conflict (correlation_id, domain) do update
                        set status = excluded.status,
                            reason_code = excluded.reason_code,
                            evidence = excluded.evidence,
                            observed_at = excluded.observed_at
                        """)
                .setParameter("correlationId", correlationId)
                .setParameter("accountId", accountId)
                .setParameter("domain", readiness.domain().name())
                .setParameter("status", readiness.status().name())
                .setParameter("reasonCode", readiness.reasonCode())
                .setParameter("evidence", readiness.evidence())
                .setParameter("observedAt", utc(readiness.observedAt()))
                .executeUpdate();
    }

    @Override
    @Transactional
    public boolean closeIfReady(
            AccountClosureCandidate candidate,
            UUID correlationId,
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
                          and account_id = :accountId
                          and status in ('FROZEN', 'SETTLED')
                        """)
                .setParameter("correlationId", correlationId)
                .setParameter("accountId", candidate.accountId())
                .getSingleResult();
        if (readyCount.intValue() != ClosureDomain.values().length) {
            return false;
        }

        String policyVersion = latestEffectivePolicy(closedAt);
        long nextVersion = version + 1;
        UUID eventId = UUID.randomUUID();
        var closedAtUtc = utc(closedAt);
        entityManager.createNativeQuery("""
                        insert into identity.account_lifecycle_events
                            (id, account_id, event_sequence, previous_event_id, lifecycle_version,
                             previous_status, new_status, command_type, actor_type, actor_id,
                             correlation_id, idempotency_key, request_hash, reason_code,
                             retention_policy_version, occurred_at)
                        values (:eventId, :accountId, :nextVersion, :previousEventId, :nextVersion,
                                'CLOSING', 'CLOSED', 'ACCOUNT_CLOSED', 'SYSTEM', 'account-closure-coordinator',
                                :correlationId, :idempotencyKey, md5(:idempotencyKey) || md5(:idempotencyKey || ':2'),
                                'WITHDRAWAL_COMPLETED', :policyVersion, :closedAt)
                        """)
                .setParameter("eventId", eventId)
                .setParameter("accountId", candidate.accountId())
                .setParameter("nextVersion", nextVersion)
                .setParameter("previousEventId", account[1])
                .setParameter("correlationId", correlationId)
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("policyVersion", policyVersion)
                .setParameter("closedAt", closedAtUtc)
                .executeUpdate();
        int updated = entityManager.createNativeQuery("""
                        update identity.accounts
                        set lifecycle_status = 'CLOSED', status_changed_at = :closedAt,
                            lifecycle_version = :nextVersion, last_lifecycle_event_id = :eventId,
                            closed_at = :closedAt
                        where id = :accountId and lifecycle_version = :previousVersion
                          and lifecycle_status = 'CLOSING'
                        """)
                .setParameter("closedAt", closedAtUtc)
                .setParameter("nextVersion", nextVersion)
                .setParameter("eventId", eventId)
                .setParameter("accountId", candidate.accountId())
                .setParameter("previousVersion", version)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("Account lifecycle projection changed concurrently");
        }
        createRetentionSnapshot(candidate.accountId(), eventId, policyVersion, closedAtUtc);
        quarantineIdentifiers(candidate.accountId(), eventId, closedAtUtc);
        revokeBindings(candidate.accountId(), closedAtUtc);
        entityManager.createNativeQuery("""
                        update identity.account_closure_runs set closed_at = :closedAt
                        where correlation_id = :correlationId
                        """)
                .setParameter("closedAt", closedAtUtc)
                .setParameter("correlationId", correlationId)
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

    private String latestEffectivePolicy(Instant at) {
        @SuppressWarnings("unchecked")
        List<String> versions = entityManager.createNativeQuery("""
                        select version from identity.account_retention_policy_versions
                        where effective_from <= :at and approved_at <= :at
                        order by effective_from desc, version desc limit 1
                        """)
                .setParameter("at", utc(at))
                .getResultList();
        return versions.isEmpty() ? null : versions.getFirst();
    }

    private void createRetentionSnapshot(
            UUID accountId, UUID eventId, String policyVersion, OffsetDateTime closedAt) {
        if (policyVersion == null) {
            entityManager.createNativeQuery("""
                            insert into identity.account_retention_obligations
                                (account_id, lifecycle_event_id, data_category, status, failure_code)
                            select :accountId, :eventId, category, 'FAILED', 'RETENTION_POLICY_MISSING'
                            from unnest(enum_range(NULL::identity.account_data_category)) category
                            """)
                    .setParameter("accountId", accountId)
                    .setParameter("eventId", eventId)
                    .executeUpdate();
            return;
        }
        entityManager.createNativeQuery("""
                        insert into identity.account_retention_obligations
                            (account_id, lifecycle_event_id, retention_policy_version, data_category,
                             disposition, retention_days, retain_until, status)
                        select :accountId, :eventId, rule.policy_version, category,
                               rule.disposition, rule.retention_days,
                               case when rule.retention_days is null then null
                                    else cast(:closedAt as timestamptz) + make_interval(days => rule.retention_days) end,
                               case when rule.policy_version is null then 'FAILED'::identity.retention_obligation_status
                                    else 'PENDING'::identity.retention_obligation_status end
                        from unnest(enum_range(NULL::identity.account_data_category)) category
                        left join identity.account_retention_policy_rules rule
                          on rule.policy_version = :policyVersion and rule.data_category = category
                        """)
                .setParameter("accountId", accountId)
                .setParameter("eventId", eventId)
                .setParameter("closedAt", closedAt)
                .setParameter("policyVersion", policyVersion)
                .executeUpdate();
        // A partial policy must fail the whole close transaction, never produce an ambiguous obligation.
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.account_retention_obligations
                        where lifecycle_event_id = :eventId
                        """)
                .setParameter("eventId", eventId)
                .getSingleResult();
        if (count.intValue() != 8) {
            throw new IllegalStateException("Approved retention policy is incomplete");
        }
    }

    private void quarantineIdentifiers(UUID accountId, UUID eventId, OffsetDateTime closedAt) {
        entityManager.createNativeQuery("""
                        insert into identity.account_identifier_quarantines
                            (account_id, lifecycle_event_id, identifier_kind, provider_code,
                             identifier_fingerprint, fingerprint_key_version,
                             quarantined_at, reuse_eligible_at)
                        select :accountId, :eventId, 'EMAIL', 'EMAIL', email_lookup_hmac,
                               email_lookup_key_version, cast(:closedAt as timestamptz),
                               cast(:closedAt as timestamptz) + interval '30 days'
                        from identity.account_emails
                        where account_id = :accountId and email_lookup_hmac is not null
                        on conflict do nothing
                        """)
                .setParameter("accountId", accountId)
                .setParameter("eventId", eventId)
                .setParameter("closedAt", closedAt)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.account_identifier_quarantines
                            (account_id, lifecycle_event_id, identifier_kind, provider_code,
                             identifier_fingerprint, fingerprint_key_version,
                             quarantined_at, reuse_eligible_at)
                        select :accountId, :eventId, 'OIDC_SUBJECT', provider.code,
                               login.provider_subject_hmac, login.subject_key_version,
                               cast(:closedAt as timestamptz), cast(:closedAt as timestamptz) + interval '30 days'
                        from identity.login_identities login
                        join identity.auth_providers provider on provider.id = login.provider_id
                        where login.account_id = :accountId and login.provider_subject_hmac is not null
                        on conflict do nothing
                        """)
                .setParameter("accountId", accountId)
                .setParameter("eventId", eventId)
                .setParameter("closedAt", closedAt)
                .executeUpdate();
    }

    private void revokeBindings(UUID accountId, OffsetDateTime closedAt) {
        entityManager.createNativeQuery("""
                        update identity.account_emails
                        set status = 'REVOKED', revoked_at = coalesce(revoked_at, :closedAt)
                        where account_id = :accountId
                        """)
                .setParameter("accountId", accountId)
                .setParameter("closedAt", closedAt)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set status = 'DISABLED', disabled_at = coalesce(disabled_at, :closedAt),
                            disabled_reason_code = 'ACCOUNT_CLOSED'
                        where account_id = :accountId and status in ('ACTIVE', 'PENDING')
                        """)
                .setParameter("accountId", accountId)
                .setParameter("closedAt", closedAt)
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
