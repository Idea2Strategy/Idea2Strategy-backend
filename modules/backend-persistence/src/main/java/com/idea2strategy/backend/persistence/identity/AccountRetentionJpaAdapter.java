package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.accountretention.RetentionCandidate;
import com.idea2strategy.backend.application.accountretention.RetentionExecutionResult;
import com.idea2strategy.backend.application.accountretention.RetentionExecutionStore;
import com.idea2strategy.backend.application.identity.IdentifierAdvisoryLockKey;
import com.idea2strategy.backend.application.identity.IdentifierFingerprint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountRetentionJpaAdapter implements RetentionExecutionStore {
    private final EntityManager entityManager;

    public AccountRetentionJpaAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueAccounts(int limit, Instant now) {
        @SuppressWarnings("unchecked")
        List<UUID> rows = entityManager.createNativeQuery("""
                        select obligation.account_id
                        from identity.account_retention_obligations obligation
                        join identity.accounts account on account.id = obligation.account_id
                        where account.lifecycle_status = 'CLOSED'
                          and (obligation.disposition = 'RETAIN' or obligation.retain_until <= :now)
                          and obligation.status in ('PENDING', 'HELD')
                          and not exists (
                              select 1 from identity.account_legal_holds hold
                              where hold.account_id = obligation.account_id
                                and hold.data_category = obligation.data_category
                                and hold.status = 'ACTIVE'
                          )
                        group by obligation.account_id
                        order by min(obligation.retain_until) nulls first, obligation.account_id
                        limit :limit
                        """)
                .setParameter("now", utc(now))
                .setParameter("limit", limit)
                .getResultList();
        return List.copyOf(rows);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<RetentionExecutionResult> executeAccount(
            UUID accountId, UUID correlationId, Instant now) {
        lockAccount(accountId);
        entityManager.createNativeQuery("select id from identity.accounts where id = :accountId for update")
                .setParameter("accountId", accountId).getSingleResult();
        List<RetentionCandidate> candidates = dueObligations(accountId, now, true);
        return candidates.stream()
                .sorted(java.util.Comparator.comparing(RetentionCandidate::dataCategory)
                        .thenComparing(RetentionCandidate::obligationId))
                .map(candidate -> executeLocked(candidate, correlationId, now))
                .toList();
    }

    private RetentionExecutionResult executeLocked(
            RetentionCandidate candidate, UUID correlationId, Instant now) {
        Object[] obligation;
        try {
            obligation = (Object[]) entityManager.createNativeQuery("""
                            select cast(data_category as text), cast(disposition as text),
                                   retention_days, retention_policy_version, retain_until
                            from identity.account_retention_obligations
                            where id = :id and account_id = :accountId
                              and status in ('PENDING', 'HELD')
                            for update
                            """)
                    .setParameter("id", candidate.obligationId())
                    .setParameter("accountId", candidate.accountId())
                    .getSingleResult();
        } catch (NoResultException ignored) {
            return RetentionExecutionResult.SKIPPED;
        }

        String category = (String) obligation[0];
        String disposition = (String) obligation[1];
        if (!category.equals(candidate.dataCategory()) || !hasUniqueCanonicalSnapshot(candidate.obligationId())) {
            failClosed(candidate, correlationId, "RETENTION_POLICY_MISSING", now);
            return RetentionExecutionResult.SKIPPED;
        }
        if (!"RETAIN".equals(disposition) && instant(obligation[4]).isAfter(now)) {
            return RetentionExecutionResult.SKIPPED;
        }
        if (activeHold(candidate.accountId(), category)) {
            entityManager.createNativeQuery("""
                            update identity.account_retention_obligations
                            set status = 'HELD', failure_code = null
                            where id = :id
                            """)
                    .setParameter("id", candidate.obligationId())
                    .executeUpdate();
            record(candidate, correlationId, "HELD", null, "{}", now);
            return RetentionExecutionResult.HELD;
        }
        entityManager.createNativeQuery("""
                        update identity.account_retention_obligations
                        set status = 'PENDING'
                        where id = :id and status = 'HELD'
                        """).setParameter("id", candidate.obligationId()).executeUpdate();

        switch (category) {
            case "PROFILE" -> anonymizeProfile(candidate.accountId(), now);
            case "CONTACT_IDENTIFIER" -> releaseIdentifiers(candidate.accountId(), now);
            case "AUTH_CREDENTIAL" -> deleteCredentials(candidate.accountId(), now);
            case "BOT_STRATEGY_PRIVATE_DATA" -> deletePrivateAssets(candidate.accountId());
            case "COMPETITION_RESULT_EVIDENCE" -> anonymizeCompetition(candidate.accountId(), now);
            case "OPERATIONS_DELIVERY_LOG" -> deleteDeliveryLogs(candidate.accountId());
            case "POLICY_CONSENT", "ACCOUNT_LIFECYCLE_AUDIT", "TRADING_FINANCIAL_RECORD",
                    "BOT_STRATEGY_EVALUATION" -> {
                if (!"RETAIN".equals(disposition)) {
                    throw new IllegalStateException("Legacy/evidence category cannot be destructive");
                }
            }
            default -> throw new IllegalStateException("Unsupported retention category: " + category);
        }

        entityManager.createNativeQuery("""
                        update identity.account_retention_obligations
                        set status = 'COMPLETED', completed_at = :now, failure_code = null
                        where id = :id
                        """)
                .setParameter("id", candidate.obligationId())
                .setParameter("now", utc(now))
                .executeUpdate();
        record(candidate, correlationId, "COMPLETED", null, "{}", now);
        return RetentionExecutionResult.COMPLETED;
    }

    private void lockAccount(UUID accountId) {
        entityManager.createNativeQuery("""
                        select pg_advisory_xact_lock(hashtextextended(
                            'account-retention:' || cast(:accountId as text), 0))
                        """).setParameter("accountId", accountId)
                .getSingleResult();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccountFailure(
            UUID accountId, UUID correlationId, String failureCode, Instant now) {
        for (var candidate : dueObligations(accountId, now, false)) {
            record(candidate, correlationId, "FAILED", failureCode, "{}", now);
        }
    }

    private List<RetentionCandidate> dueObligations(UUID accountId, Instant now, boolean lock) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select obligation.id, obligation.account_id, cast(obligation.data_category as text)
                        from identity.account_retention_obligations obligation
                        where obligation.account_id = :accountId
                          and obligation.status in ('PENDING', 'HELD')
                          and (obligation.disposition = 'RETAIN' or obligation.retain_until <= :now)
                          and not exists (
                              select 1 from identity.account_legal_holds hold
                              where hold.account_id = obligation.account_id
                                and hold.data_category = obligation.data_category
                                and hold.status = 'ACTIVE'
                          )
                        order by obligation.data_category, obligation.id
                        """ + (lock ? " for update of obligation" : ""))
                .setParameter("accountId", accountId).setParameter("now", utc(now)).getResultList();
        return rows.stream().map(row -> new RetentionCandidate(
                (UUID) row[0], (UUID) row[1], (String) row[2])).toList();
    }

    private boolean hasUniqueCanonicalSnapshot(UUID obligationId) {
        Number count = (Number) entityManager.createNativeQuery("""
                        with target as (
                            select obligation.*, event.occurred_at
                            from identity.account_retention_obligations obligation
                            join identity.account_lifecycle_events event
                              on event.id = obligation.lifecycle_event_id
                            where obligation.id = :id
                        ), complete as (
                            select policy.version, policy.effective_from
                            from identity.account_retention_policy_versions policy
                            join identity.account_retention_policy_rules rule
                              on rule.policy_version = policy.version
                            join target on policy.effective_from <= target.occurred_at
                                       and policy.approved_at <= target.occurred_at
                            group by policy.version, policy.effective_from
                            having count(*) = cardinality(enum_range(NULL::identity.account_data_category))
                               and count(distinct rule.data_category) =
                                   cardinality(enum_range(NULL::identity.account_data_category))
                        ), latest as (select max(effective_from) effective_from from complete), canonical as (
                            select complete.version from complete join latest using (effective_from)
                        )
                        select count(*)
                        from target
                        join canonical on canonical.version = target.retention_policy_version
                        join identity.account_retention_policy_rules rule
                          on rule.policy_version = canonical.version
                         and rule.data_category = target.data_category
                         and rule.disposition = target.disposition
                         and rule.retention_days is not distinct from target.retention_days
                        where (select count(*) from canonical) = 1
                        """)
                .setParameter("id", obligationId)
                .getSingleResult();
        return count.intValue() == 1;
    }

    private boolean activeHold(UUID accountId, String category) {
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.account_legal_holds
                        where account_id = :accountId and cast(data_category as text) = :category
                          and status = 'ACTIVE'
                        """)
                .setParameter("accountId", accountId)
                .setParameter("category", category)
                .getSingleResult();
        return count.intValue() > 0;
    }

    private void anonymizeProfile(UUID accountId, Instant now) {
        entityManager.createNativeQuery("delete from identity.account_preferences where account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.accounts set anonymized_at = :now
                        where id = :accountId and lifecycle_status = 'CLOSED'
                        """)
                .setParameter("accountId", accountId).setParameter("now", utc(now)).executeUpdate();
    }

    private void deleteCredentials(UUID accountId, Instant now) {
        entityManager.createNativeQuery("delete from identity.sessions where account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("delete from identity.password_reset_requests where account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("""
                        delete from identity.password_credentials credential
                        using identity.login_identities login
                        where credential.login_identity_id = login.id and login.account_id = :accountId
                        """).setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("""
                        delete from identity.recovery_codes code using identity.recovery_code_sets code_set
                        where code.recovery_code_set_id = code_set.id and code_set.account_id = :accountId
                        """).setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("delete from identity.recovery_code_sets where account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("delete from identity.email_verification_requests where account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set status = 'DISABLED', disabled_at = coalesce(disabled_at, :now),
                            disabled_reason_code = 'ACCOUNT_CLOSED'
                        where account_id = :accountId and status <> 'DISABLED'
                        """).setParameter("accountId", accountId).setParameter("now", utc(now)).executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.account_emails
                        set status = 'REVOKED', revoked_at = coalesce(revoked_at, :now)
                        where account_id = :accountId
                        """).setParameter("accountId", accountId).setParameter("now", utc(now)).executeUpdate();
    }

    private void releaseIdentifiers(UUID accountId, Instant now) {
        @SuppressWarnings("unchecked")
        List<Object[]> quarantines = entityManager.createNativeQuery("""
                        select id, identifier_kind, provider_code, identifier_fingerprint,
                               fingerprint_key_version
                        from identity.account_identifier_quarantines
                        where account_id = :accountId and released_at is null
                          and reuse_eligible_at <= :now
                        order by id for update
                        """).setParameter("accountId", accountId).setParameter("now", utc(now)).getResultList();
        for (Object[] quarantine : quarantines) {
            UUID id = (UUID) quarantine[0];
            String kind = (String) quarantine[1];
            var fingerprint = new IdentifierFingerprint(
                    (String) quarantine[3], ((Number) quarantine[4]).shortValue());
            entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .setParameter("key", IdentifierAdvisoryLockKey.of(kind, (String) quarantine[2], fingerprint))
                    .getSingleResult();
            int changed;
            if ("EMAIL".equals(kind)) {
                entityManager.createNativeQuery("delete from identity.email_verification_requests where account_id = :accountId")
                        .setParameter("accountId", accountId).executeUpdate();
                changed = entityManager.createNativeQuery("""
                                delete from identity.account_emails
                                where account_id = :accountId and email_lookup_hmac = :fingerprint
                                  and email_lookup_key_version = :keyVersion
                                """).setParameter("accountId", accountId)
                        .setParameter("fingerprint", quarantine[3])
                        .setParameter("keyVersion", quarantine[4]).executeUpdate();
            } else {
                changed = entityManager.createNativeQuery("""
                                update identity.login_identities login
                                set provider_subject_hmac = null, subject_key_version = null,
                                    status = 'DISABLED', disabled_at = coalesce(disabled_at, :now),
                                    disabled_reason_code = 'IDENTIFIER_RELEASED'
                                from identity.auth_providers provider
                                where login.provider_id = provider.id and login.account_id = :accountId
                                  and provider.code = :provider and login.provider_subject_hmac = :fingerprint
                                  and login.subject_key_version = :keyVersion
                                """).setParameter("accountId", accountId)
                        .setParameter("provider", quarantine[2]).setParameter("fingerprint", quarantine[3])
                        .setParameter("keyVersion", quarantine[4]).setParameter("now", utc(now)).executeUpdate();
            }
            if (changed != 1) throw new IllegalStateException("Identifier binding is missing or ambiguous");
            entityManager.createNativeQuery("""
                            update identity.account_identifier_quarantines
                            set released_at = :now, release_reason_code = 'QUARANTINE_EXPIRED'
                            where id = :id and released_at is null
                            """).setParameter("id", id).setParameter("now", utc(now)).executeUpdate();
        }
        Number remaining = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.account_identifier_quarantines
                        where account_id = :accountId and released_at is null
                        """).setParameter("accountId", accountId).getSingleResult();
        if (remaining.intValue() != 0) throw new IllegalStateException("Identifier quarantine is not yet releasable");
    }

    private void deletePrivateAssets(UUID accountId) {
        entityManager.createNativeQuery("""
                        delete from strategy.strategy_edit_leases lease using strategy.strategies strategy
                        where lease.strategy_id = strategy.id and strategy.owner_account_id = :accountId
                        """).setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("""
                        delete from strategy.validation_runs run using strategy.strategies strategy
                        where run.strategy_id = strategy.id and strategy.owner_account_id = :accountId
                        """).setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("""
                        delete from strategy.strategy_documents document using strategy.strategies strategy
                        where document.strategy_id = strategy.id and strategy.owner_account_id = :accountId
                        """).setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("delete from strategy.strategies where owner_account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();

        entityManager.createNativeQuery("select identity.delete_proven_private_bots(:accountId)")
                .setParameter("accountId", accountId).getSingleResult();
    }

    private void anonymizeCompetition(UUID accountId, Instant now) {
        entityManager.createNativeQuery("select backtest.anonymize_official_competition_run_owners(:accountId, :now)")
                .setParameter("accountId", accountId).setParameter("now", utc(now)).getSingleResult();
        entityManager.createNativeQuery("""
                        update bot.bots bot set owner_account_id = null, owner_anonymized_at = :now
                        where bot.owner_account_id = :accountId
                          and exists (select 1 from competition.participations p where p.bot_id = bot.id)
                        """).setParameter("accountId", accountId).setParameter("now", utc(now)).executeUpdate();
        entityManager.createNativeQuery("""
                        update competition.rooms set creator_account_id = null, creator_anonymized_at = :now
                        where creator_account_id = :accountId
                        """).setParameter("accountId", accountId).setParameter("now", utc(now)).executeUpdate();
        entityManager.createNativeQuery("""
                        update competition.participations
                        set owner_account_id = null, owner_anonymized_at = :now
                        where owner_account_id = :accountId
                        """).setParameter("accountId", accountId).setParameter("now", utc(now)).executeUpdate();
    }

    private void deleteDeliveryLogs(UUID accountId) {
        entityManager.createNativeQuery("""
                        delete from operations.delivery_attempts attempt using operations.notifications notification
                        where attempt.notification_id = notification.id and notification.account_id = :accountId
                        """).setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("delete from operations.notifications where account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();
        entityManager.createNativeQuery("delete from operations.notification_preferences where account_id = :accountId")
                .setParameter("accountId", accountId).executeUpdate();
    }

    private void failClosed(RetentionCandidate candidate, UUID correlationId, String code, Instant now) {
        entityManager.createNativeQuery("""
                        update identity.account_retention_obligations
                        set status = 'FAILED', failure_code = :code
                        where id = :id
                        """).setParameter("id", candidate.obligationId()).setParameter("code", code).executeUpdate();
        record(candidate, correlationId, "FAILED", code, "{}", now);
    }

    private void record(RetentionCandidate candidate, UUID correlationId, String outcome,
                        String failureCode, String evidence, Instant now) {
        entityManager.createNativeQuery("""
                        insert into identity.account_retention_execution_attempts
                            (obligation_id, account_id, data_category, correlation_id,
                             outcome, failure_code, evidence, occurred_at)
                        values (:obligationId, :accountId,
                                cast(:category as identity.account_data_category), :correlationId,
                                :outcome, :failureCode, cast(:evidence as jsonb), :now)
                        """).setParameter("obligationId", candidate.obligationId())
                .setParameter("accountId", candidate.accountId()).setParameter("category", candidate.dataCategory())
                .setParameter("correlationId", correlationId).setParameter("outcome", outcome)
                .setParameter("failureCode", failureCode).setParameter("evidence", evidence)
                .setParameter("now", utc(now)).executeUpdate();
    }

    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }
}
