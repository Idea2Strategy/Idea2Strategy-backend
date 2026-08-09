package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.PendingRegistrationCleanupPort;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PendingRegistrationCleanupJpaAdapter implements PendingRegistrationCleanupPort {
    private final EntityManager entityManager;

    public PendingRegistrationCleanupJpaAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public int purgeExpired(Instant cutoff, Instant purgedAt, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        @SuppressWarnings("unchecked")
        List<UUID> candidates = entityManager.createNativeQuery("""
                        select account.id
                        from identity.accounts account
                        where account.lifecycle_status = cast('PENDING_VERIFICATION' as identity.account_lifecycle_status)
                          and coalesce((
                              select max(request.requested_at)
                              from identity.email_verification_requests request
                              where request.account_id = account.id
                          ), account.created_at) <= :cutoff
                        order by account.created_at, account.id
                        limit :limit
                        for update of account skip locked
                        """)
                .setParameter("cutoff", utc(cutoff))
                .setParameter("limit", limit)
                .getResultList();

        int purged = 0;
        for (UUID accountId : candidates) {
            if (purgeLocked(accountId, cutoff, purgedAt)) {
                purged++;
            }
        }
        return purged;
    }

    private boolean purgeLocked(UUID accountId, Instant cutoff, Instant purgedAt) {
        Object[] account = (Object[]) entityManager.createNativeQuery("""
                        select lifecycle_status::text, lifecycle_version, last_lifecycle_event_id,
                               coalesce((
                                   select max(request.requested_at)
                                   from identity.email_verification_requests request
                                   where request.account_id = account.id
                               ), account.created_at)
                        from identity.accounts account
                        where id = :accountId
                        for update
                        """)
                .setParameter("accountId", accountId)
                .getSingleResult();
        if (!"PENDING_VERIFICATION".equals(account[0]) || timestamp(account[3]).isAfter(cutoff)) {
            return false;
        }

        UUID eventId = UUID.randomUUID();
        long nextVersion = ((Number) account[1]).longValue() + 1;
        UUID previousEventId = (UUID) account[2];
        OffsetDateTime now = utc(purgedAt);
        String idempotencyKey = "pending-registration-expired:" + accountId;
        entityManager.createNativeQuery("""
                        insert into identity.account_lifecycle_events
                            (id, account_id, event_sequence, previous_event_id, lifecycle_version,
                             previous_status, new_status, command_type, actor_type, actor_id,
                             correlation_id, idempotency_key, request_hash, reason_code, occurred_at)
                        values (:eventId, :accountId, :version, :previousEventId, :version,
                                cast('PENDING_VERIFICATION' as identity.account_lifecycle_status),
                                cast('CLOSED' as identity.account_lifecycle_status),
                                'PENDING_REGISTRATION_EXPIRED', 'SYSTEM', null,
                                :correlationId, :idempotencyKey,
                                md5(:idempotencyKey) || md5(:idempotencyKey || ':2'),
                                'PENDING_REGISTRATION_EXPIRED', :now)
                        """)
                .setParameter("eventId", eventId)
                .setParameter("accountId", accountId)
                .setParameter("version", nextVersion)
                .setParameter("previousEventId", previousEventId)
                .setParameter("correlationId", UUID.randomUUID())
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("now", now)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.accounts
                        set lifecycle_status = cast('CLOSED' as identity.account_lifecycle_status),
                            status_changed_at = :now,
                            lifecycle_version = :version,
                            last_lifecycle_event_id = :eventId,
                            closed_at = :now
                        where id = :accountId
                          and lifecycle_status = cast('PENDING_VERIFICATION' as identity.account_lifecycle_status)
                        """)
                .setParameter("now", now)
                .setParameter("version", nextVersion)
                .setParameter("eventId", eventId)
                .setParameter("accountId", accountId)
                .executeUpdate();

        entityManager.createNativeQuery(
                        "delete from identity.email_verification_requests where account_id = :accountId")
                .setParameter("accountId", accountId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        delete from identity.password_credentials credential
                        using identity.login_identities login
                        where credential.login_identity_id = login.id and login.account_id = :accountId
                        """)
                .setParameter("accountId", accountId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set status = cast('DISABLED' as identity.login_identity_status),
                            disabled_at = :now,
                            disabled_reason_code = 'PENDING_REGISTRATION_EXPIRED'
                        where account_id = :accountId
                          and status = cast('PENDING' as identity.login_identity_status)
                        """)
                .setParameter("now", now)
                .setParameter("accountId", accountId)
                .executeUpdate();
        entityManager.createNativeQuery("delete from identity.account_preferences where account_id = :accountId")
                .setParameter("accountId", accountId)
                .executeUpdate();
        entityManager.createNativeQuery("delete from identity.account_security_states where account_id = :accountId")
                .setParameter("accountId", accountId)
                .executeUpdate();
        entityManager.createNativeQuery("delete from identity.account_emails where account_id = :accountId")
                .setParameter("accountId", accountId)
                .executeUpdate();
        return true;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant timestamp(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }
}
