package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.accountclosure.IdentifierQuarantinePort;
import com.idea2strategy.backend.application.accountclosure.RetentionDisposition;
import com.idea2strategy.backend.application.accountclosure.RetentionObligation;
import com.idea2strategy.backend.application.accountclosure.RetentionObligationPort;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AccountRetentionJpaStore implements RetentionObligationPort, IdentifierQuarantinePort {
    private final EntityManager entityManager;

    public AccountRetentionJpaStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetentionObligation> findDueObligations(int limit, Instant now) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, account_id, cast(data_category as text), cast(disposition as text), retain_until
                        from identity.account_retention_obligations
                        where status = 'PENDING'
                          and (disposition = 'RETAIN' or retain_until <= :now)
                        order by retain_until nulls first, id
                        limit :limit
                        """)
                .setParameter("now", utc(now))
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(row -> new RetentionObligation(
                (UUID) row[0], (UUID) row[1], (String) row[2],
                RetentionDisposition.valueOf((String) row[3]), instant(row[4]))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveLegalHold(UUID accountId, String dataCategory) {
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.account_legal_holds
                        where account_id = :accountId
                          and data_category = cast(:category as identity.account_data_category)
                          and status = 'ACTIVE'
                        """)
                .setParameter("accountId", accountId)
                .setParameter("category", dataCategory)
                .getSingleResult();
        return count.intValue() > 0;
    }

    @Override public void markHeld(UUID id, Instant at) { updateStatus(id, "HELD", null, null); }
    @Override public void markCompleted(UUID id, Instant at) { updateStatus(id, "COMPLETED", null, utc(at)); }
    @Override public void markFailed(UUID id, String code, Instant at) { updateStatus(id, "FAILED", code, null); }

    @Override
    @Transactional
    public int resumeReleasedHolds(Instant at) {
        return entityManager.createNativeQuery("""
                        update identity.account_retention_obligations obligation
                        set status = 'PENDING'
                        where obligation.status = 'HELD'
                          and not exists (
                              select 1 from identity.account_legal_holds hold
                              where hold.account_id = obligation.account_id
                                and hold.data_category = obligation.data_category
                                and hold.status = 'ACTIVE')
                        """).executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueIdentifier> findDueIdentifiers(int limit, Instant now) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, account_id, identifier_kind
                        from identity.account_identifier_quarantines
                        where released_at is null and reuse_eligible_at <= :now
                        order by reuse_eligible_at, id
                        limit :limit
                        """)
                .setParameter("now", utc(now))
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(row -> new DueIdentifier((UUID) row[0], (UUID) row[1], (String) row[2])).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasReuseBlockingLegalHold(UUID accountId) {
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.account_legal_holds
                        where account_id = :accountId and data_category = 'CONTACT_IDENTIFIER'
                          and status = 'ACTIVE' and blocks_identifier_reuse
                        """)
                .setParameter("accountId", accountId)
                .getSingleResult();
        return count.intValue() > 0;
    }

    @Override
    @Transactional
    public boolean releaseBindingAndQuarantine(DueIdentifier identifier, Instant releasedAt) {
        var releasedAtUtc = utc(releasedAt);
        int bindings;
        if ("EMAIL".equals(identifier.identifierKind())) {
            bindings = entityManager.createNativeQuery("""
                            update identity.account_emails email
                            set email_lookup_hmac = null, email_lookup_key_version = null
                            from identity.account_identifier_quarantines quarantine
                            where quarantine.id = :quarantineId
                              and quarantine.account_id = email.account_id
                              and quarantine.identifier_fingerprint = email.email_lookup_hmac
                              and quarantine.fingerprint_key_version = email.email_lookup_key_version
                              and quarantine.released_at is null
                            """)
                    .setParameter("quarantineId", identifier.quarantineId())
                    .executeUpdate();
        } else if ("OIDC_SUBJECT".equals(identifier.identifierKind())) {
            bindings = entityManager.createNativeQuery("""
                            update identity.login_identities login
                            set provider_subject_hmac = null, subject_key_version = null
                            from identity.account_identifier_quarantines quarantine,
                                 identity.auth_providers provider
                            where quarantine.id = :quarantineId
                              and quarantine.account_id = login.account_id
                              and provider.id = login.provider_id
                              and provider.code = quarantine.provider_code
                              and quarantine.identifier_fingerprint = login.provider_subject_hmac
                              and quarantine.fingerprint_key_version = login.subject_key_version
                              and quarantine.released_at is null
                            """)
                    .setParameter("quarantineId", identifier.quarantineId())
                    .executeUpdate();
        } else {
            throw new IllegalArgumentException("Unsupported identifier kind: " + identifier.identifierKind());
        }
        if (bindings != 1) {
            return false;
        }
        return entityManager.createNativeQuery("""
                        update identity.account_identifier_quarantines
                        set released_at = :releasedAt, release_reason_code = 'QUARANTINE_EXPIRED'
                        where id = :quarantineId and released_at is null
                          and reuse_eligible_at <= :releasedAt
                        """)
                .setParameter("releasedAt", releasedAtUtc)
                .setParameter("quarantineId", identifier.quarantineId())
                .executeUpdate() == 1;
    }

    @Transactional
    protected void updateStatus(UUID id, String status, String failureCode, OffsetDateTime completedAt) {
        entityManager.createNativeQuery("""
                        update identity.account_retention_obligations
                        set status = cast(:status as identity.retention_obligation_status),
                            failure_code = :failureCode, completed_at = :completedAt
                        where id = :id and status = 'PENDING'
                        """)
                .setParameter("status", status)
                .setParameter("failureCode", failureCode)
                .setParameter("completedAt", completedAt)
                .setParameter("id", id)
                .executeUpdate();
    }

    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }
}
