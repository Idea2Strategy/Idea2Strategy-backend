package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.AccountLifecycleCommandPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandType;
import com.idea2strategy.backend.application.identity.AccountLifecycleDecision;
import com.idea2strategy.backend.application.identity.AccountLifecycleRejectedException;
import com.idea2strategy.backend.application.identity.AccountLifecycleResult;
import com.idea2strategy.backend.application.identity.AccountLifecycleSnapshot;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountLifecycleJpaCommandAdapter implements AccountLifecycleCommandPort {
    private final EntityManager entityManager;

    public AccountLifecycleJpaCommandAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public AccountLifecycleResult executeAtomically(
            UUID accountId,
            AccountLifecycleCommandType commandType,
            String idempotencyKey,
            String requestHash,
            UUID correlationId,
            AccountLifecycleDecision decision) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(commandType, "commandType");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(decision, "decision");

        AccountRow account = lockAccount(accountId);
        String persistedCommandType = persistedCommandType(commandType);
        AccountLifecycleResult replay = findReplay(
                accountId, persistedCommandType, idempotencyKey, requestHash);
        if (replay != null) {
            return replay;
        }

        var mutation = decision.decide(account.snapshot());
        if (mutation.isEmpty()) {
            AccountLifecycleResult result = AccountLifecycleResult.skipped(account.snapshot());
            insertReceipt(
                    accountId,
                    persistedCommandType,
                    idempotencyKey,
                    requestHash,
                    responseStatus(commandType),
                    null,
                    result,
                    OffsetDateTime.now(ZoneOffset.UTC));
            return result;
        }

        var applied = mutation.orElseThrow();
        long nextVersion = account.version() + 1;
        UUID eventId = UUID.randomUUID();
        OffsetDateTime occurredAt = utc(applied.occurredAt());
        int eventInserts = entityManager.createNativeQuery("""
                        insert into identity.account_lifecycle_events
                            (id, account_id, event_sequence, previous_event_id, lifecycle_version,
                             previous_status, new_status, command_type, actor_type, actor_id,
                             correlation_id, idempotency_key, request_hash, reason_code,
                             cancellation_deadline_at, dormancy_basis_at, occurred_at)
                        values (:id, :accountId, :version, :previousEventId, :version,
                                cast(:previousStatus as identity.account_lifecycle_status),
                                cast(:newStatus as identity.account_lifecycle_status),
                                :commandType, :actorType, :actorId, :correlationId,
                                :idempotencyKey, :requestHash, :reasonCode,
                                :cancellationDeadlineAt, :dormancyBasisAt, :occurredAt)
                        """)
                .setParameter("id", eventId)
                .setParameter("accountId", accountId)
                .setParameter("version", nextVersion)
                .setParameter("previousEventId", account.lastEventId())
                .setParameter("previousStatus", account.status().name())
                .setParameter("newStatus", applied.status().name())
                .setParameter("commandType", persistedCommandType)
                .setParameter("actorType", commandType == AccountLifecycleCommandType.MARK_DORMANT ? "SYSTEM" : "ACCOUNT")
                .setParameter("actorId", commandType == AccountLifecycleCommandType.MARK_DORMANT
                        ? "dormancy-scheduler"
                        : accountId.toString())
                .setParameter("correlationId", correlationId)
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("requestHash", requestHash)
                .setParameter("reasonCode", applied.reasonCode())
                .setParameter("cancellationDeadlineAt", utc(applied.cancellationDeadlineAt()))
                .setParameter("dormancyBasisAt", commandType == AccountLifecycleCommandType.MARK_DORMANT
                        ? utc(account.lastSuccessfulAuthAt())
                        : null)
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();
        if (eventInserts != 1) {
            throw new IllegalStateException("Account lifecycle event was not appended");
        }

        int projectionUpdates = entityManager.createNativeQuery("""
                        update identity.accounts
                        set lifecycle_status = cast(:newStatus as identity.account_lifecycle_status),
                            status_changed_at = :occurredAt,
                            lifecycle_version = :version,
                            last_lifecycle_event_id = :eventId,
                            dormant_at = case
                                when :newStatus = 'DORMANT' then coalesce(dormant_at, :occurredAt)
                                when :newStatus = 'ACTIVE' then null
                                else dormant_at
                            end,
                            withdrawal_requested_at = :withdrawalRequestedAt,
                            cancellation_deadline_at = :cancellationDeadlineAt,
                            closing_previous_status = cast(:closingPreviousStatus as identity.account_lifecycle_status),
                            closed_at = case when :newStatus = 'CLOSED' then :occurredAt else closed_at end
                        where id = :accountId and lifecycle_version = :previousVersion
                        """)
                .setParameter("newStatus", applied.status().name())
                .setParameter("occurredAt", occurredAt)
                .setParameter("version", nextVersion)
                .setParameter("eventId", eventId)
                .setParameter("withdrawalRequestedAt", utc(applied.withdrawalRequestedAt()))
                .setParameter("cancellationDeadlineAt", utc(applied.cancellationDeadlineAt()))
                .setParameter("closingPreviousStatus", applied.closingPreviousStatus() == null
                        ? null
                        : applied.closingPreviousStatus().name())
                .setParameter("accountId", accountId)
                .setParameter("previousVersion", account.version())
                .executeUpdate();
        if (projectionUpdates != 1) {
            throw new IllegalStateException("Account lifecycle projection changed concurrently");
        }

        if (invalidatesSessions(applied.status())) {
            insertAccessRevokedOutbox(
                    accountId,
                    nextVersion,
                    persistedCommandType,
                    correlationId,
                    applied.status(),
                    eventId,
                    occurredAt);
            invalidateSessions(accountId, applied.reasonCode(), occurredAt);
        }
        AccountLifecycleResult result = AccountLifecycleResult.applied(applied.applyTo(account.snapshot()));
        insertReceipt(
                accountId,
                persistedCommandType,
                idempotencyKey,
                requestHash,
                responseStatus(commandType),
                eventId,
                result,
                occurredAt);
        return result;
    }

    private AccountRow lockAccount(UUID accountId) {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                            select id, cast(lifecycle_status as text), lifecycle_version,
                                   last_successful_auth_at, withdrawal_requested_at,
                                   cancellation_deadline_at, cast(closing_previous_status as text),
                                   last_lifecycle_event_id
                            from identity.accounts
                            where id = :accountId
                            for update
                            """)
                    .setParameter("accountId", accountId)
                    .getSingleResult();
            return new AccountRow(
                    (UUID) row[0],
                    AccountLifecycleStatus.valueOf((String) row[1]),
                    ((Number) row[2]).longValue(),
                    instant(row[3]),
                    instant(row[4]),
                    instant(row[5]),
                    row[6] == null ? null : AccountLifecycleStatus.valueOf((String) row[6]),
                    (UUID) row[7]);
        } catch (NoResultException exception) {
            throw new AccountLifecycleRejectedException("ACCOUNT_NOT_FOUND");
        }
    }

    private AccountLifecycleResult findReplay(
            UUID accountId, String commandType, String idempotencyKey, String requestHash) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select request_hash,
                               response_document ->> 'status',
                               cast(response_document ->> 'version' as bigint),
                               cast(response_document ->> 'withdrawalRequestedAt' as timestamptz),
                               cast(response_document ->> 'cancellationDeadlineAt' as timestamptz),
                               cast(response_document ->> 'applied' as boolean)
                        from identity.account_lifecycle_command_receipts
                        where account_id = :accountId
                          and command_type = :commandType
                          and idempotency_key = :idempotencyKey
                        """)
                .setParameter("accountId", accountId)
                .setParameter("commandType", commandType)
                .setParameter("idempotencyKey", idempotencyKey)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.getFirst();
        if (!requestHash.equals(row[0])) {
            throw new AccountLifecycleRejectedException("IDEMPOTENCY_KEY_REUSED");
        }
        AccountLifecycleStatus status = AccountLifecycleStatus.valueOf((String) row[1]);
        return new AccountLifecycleResult(
                accountId,
                status,
                ((Number) row[2]).longValue(),
                instant(row[3]),
                instant(row[4]),
                (Boolean) row[5]);
    }

    private void insertReceipt(
            UUID accountId,
            String commandType,
            String idempotencyKey,
            String requestHash,
            int responseStatus,
            UUID lifecycleEventId,
            AccountLifecycleResult result,
            OffsetDateTime completedAt) {
        int inserts = entityManager.createNativeQuery("""
                        insert into identity.account_lifecycle_command_receipts
                            (account_id, command_type, idempotency_key, request_hash,
                             response_status, response_code, response_document,
                             lifecycle_event_id, completed_at)
                        values (:accountId, :commandType, :idempotencyKey, :requestHash,
                                :responseStatus, null,
                                jsonb_build_object(
                                    'accountId', cast(:accountId as text),
                                    'status', :status,
                                    'version', :version,
                                    'withdrawalRequestedAt', cast(:withdrawalRequestedAt as text),
                                    'cancellationDeadlineAt', cast(:cancellationDeadlineAt as text),
                                    'applied', :applied),
                                :lifecycleEventId, :completedAt)
                        """)
                .setParameter("accountId", accountId)
                .setParameter("commandType", commandType)
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("requestHash", requestHash)
                .setParameter("responseStatus", responseStatus)
                .setParameter("status", result.status().name())
                .setParameter("version", result.version())
                .setParameter("withdrawalRequestedAt", utc(result.withdrawalRequestedAt()))
                .setParameter("cancellationDeadlineAt", utc(result.cancellationDeadlineAt()))
                .setParameter("applied", result.applied())
                .setParameter("lifecycleEventId", lifecycleEventId)
                .setParameter("completedAt", completedAt)
                .executeUpdate();
        if (inserts != 1) {
            throw new IllegalStateException("Account lifecycle command receipt was not stored");
        }
    }

    private void invalidateSessions(UUID accountId, String reasonCode, OffsetDateTime occurredAt) {
        int securityUpdates = entityManager.createNativeQuery("""
                        update identity.account_security_states
                        set auth_epoch = auth_epoch + 1,
                            sessions_revoked_before = :occurredAt,
                            updated_at = :occurredAt
                        where account_id = :accountId
                        """)
                .setParameter("occurredAt", occurredAt)
                .setParameter("accountId", accountId)
                .executeUpdate();
        if (securityUpdates != 1) {
            throw new IllegalStateException("Account security state is missing");
        }
        entityManager.createNativeQuery("""
                        update identity.sessions
                        set revoked_at = :occurredAt, revoke_reason_code = :reasonCode
                        where account_id = :accountId and revoked_at is null
                        """)
                .setParameter("occurredAt", occurredAt)
                .setParameter("reasonCode", reasonCode)
                .setParameter("accountId", accountId)
                .executeUpdate();
    }

    private void insertAccessRevokedOutbox(
            UUID accountId,
            long version,
            String cause,
            UUID correlationId,
            AccountLifecycleStatus status,
            UUID lifecycleEventId,
            OffsetDateTime occurredAt) {
        entityManager.createNativeQuery("""
                        insert into operations.outbox_messages
                            (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                             event_schema_version, payload_document, idempotency_key, created_at)
                        values (gen_random_uuid(), 'identity', :accountId, :version, 'ACCOUNT_ACCESS_REVOKED',
                                'account-lifecycle.v1',
                                jsonb_build_object(
                                    'accountId', cast(:accountId as text),
                                    'lifecycleStatus', :status,
                                    'cause', :cause,
                                    'version', :version,
                                    'lifecycleEventId', cast(:lifecycleEventId as text),
                                    'correlationId', cast(:correlationId as text),
                                    'occurredAt', cast(:occurredAt as text)),
                                :idempotencyKey, :occurredAt)
                        """)
                .setParameter("accountId", accountId)
                .setParameter("version", version)
                .setParameter("cause", cause)
                .setParameter("status", status.name())
                .setParameter("lifecycleEventId", lifecycleEventId)
                .setParameter("correlationId", correlationId)
                .setParameter("occurredAt", occurredAt)
                .setParameter("idempotencyKey", "account-lifecycle:" + lifecycleEventId)
                .executeUpdate();
    }

    private static boolean invalidatesSessions(AccountLifecycleStatus status) {
        return status == AccountLifecycleStatus.CLOSING
                || status == AccountLifecycleStatus.DORMANT
                || status == AccountLifecycleStatus.CLOSED;
    }

    private static int responseStatus(AccountLifecycleCommandType commandType) {
        return commandType == AccountLifecycleCommandType.REQUEST_WITHDRAWAL ? 202 : 200;
    }

    private static String persistedCommandType(AccountLifecycleCommandType commandType) {
        return switch (commandType) {
            case REQUEST_WITHDRAWAL -> "WITHDRAWAL_REQUESTED";
            case CANCEL_WITHDRAWAL -> "WITHDRAWAL_CANCELLED";
            case MARK_DORMANT -> "ACCOUNT_DORMANT";
        };
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }

    private record AccountRow(
            UUID accountId,
            AccountLifecycleStatus status,
            long version,
            Instant lastSuccessfulAuthAt,
            Instant withdrawalRequestedAt,
            Instant cancellationDeadlineAt,
            AccountLifecycleStatus closingPreviousStatus,
            UUID lastEventId) {
        AccountLifecycleSnapshot snapshot() {
            return new AccountLifecycleSnapshot(
                    accountId,
                    status,
                    version,
                    lastSuccessfulAuthAt,
                    withdrawalRequestedAt,
                    cancellationDeadlineAt,
                    closingPreviousStatus);
        }
    }
}
