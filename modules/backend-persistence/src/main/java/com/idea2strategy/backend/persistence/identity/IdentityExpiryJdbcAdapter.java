package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.delegation.DelegatedCredentialExpiryPort;
import com.idea2strategy.backend.application.delegation.DelegatedCredentialExpiryPort.Kind;
import com.idea2strategy.backend.application.identity.RefreshTokenFamilyExpiryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL DB-clock expiry transitions shared by backend-batch workers. */
public class IdentityExpiryJdbcAdapter implements RefreshTokenFamilyExpiryPort, DelegatedCredentialExpiryPort {
    private static final String REFRESH_TOKEN_REASON = "REFRESH_TOKEN_EXPIRED";
    private static final String DELEGATED_REASON = "DELEGATED_CREDENTIAL_EXPIRED";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public IdentityExpiryJdbcAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public List<RefreshTokenFamilyExpiryPort.Identity> findDueRefreshTokenFamilies(int limit) {
        requireLimit(limit);
        return jdbc.query("""
                select account_id, id, expires_at
                  from identity.refresh_token_families
                 where revoked_at is null
                   and expires_at <= clock_timestamp()
                 order by expires_at, id
                 limit ?
                """, (rs, row) -> new RefreshTokenFamilyExpiryPort.Identity(
                rs.getObject("account_id", UUID.class),
                rs.getObject("id", UUID.class),
                rs.getTimestamp("expires_at").toInstant()), limit);
    }

    @Override
    public RefreshTokenFamilyExpiryPort.Result expire(RefreshTokenFamilyExpiryPort.Identity identity, UUID correlationId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.execute(status -> expireRefreshTokenFamily(identity, correlationId));
    }

    @Override
    public List<DelegatedCredentialExpiryPort.Identity> findDueCredentials(int limit) {
        requireLimit(limit);
        return jdbc.query("""
                select kind, authorization_id, credential_id, expires_at
                  from (
                    select 'CREDENTIAL' as kind, c.authorization_id, c.id as credential_id, c.expires_at
                      from identity.delegated_credentials c
                      join identity.delegated_authorizations a on a.id = c.authorization_id
                     where c.revoked_at is null and a.status = 'ACTIVE'
                       and c.expires_at <= clock_timestamp()
                       and not exists (
                           select 1 from operations.audit_events e
                            where e.idempotency_key like concat('delegated-token-expiry:', c.id, ':%'))
                    union all
                    select 'AUTHORIZATION', a.id, null::uuid, a.expires_at
                      from identity.delegated_authorizations a
                     where a.status = 'ACTIVE' and a.expires_at <= clock_timestamp()
                       and not exists (
                           select 1 from identity.delegated_authorization_events e
                            where e.authorization_id = a.id
                              and e.idempotency_key like concat('delegated-authorization-expiry:', a.id, ':%'))
                  ) due
                 order by expires_at, authorization_id, credential_id nulls last
                 limit ?
                """, (rs, row) -> new DelegatedCredentialExpiryPort.Identity(
                Kind.valueOf(rs.getString("kind")),
                rs.getObject("authorization_id", UUID.class),
                rs.getObject("credential_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant()), limit);
    }

    @Override
    public DelegatedCredentialExpiryPort.Result expire(
            DelegatedCredentialExpiryPort.Identity identity, UUID correlationId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.execute(status -> expireDelegatedCredential(identity, correlationId));
    }

    private RefreshTokenFamilyExpiryPort.Result expireRefreshTokenFamily(
            RefreshTokenFamilyExpiryPort.Identity identity, UUID correlationId) {
        jdbc.queryForObject("select id from identity.accounts where id = ? for update",
                UUID.class, identity.accountId());
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update identity.refresh_token_families
                   set revoked_at = ?, revoke_reason_code = ?
                 where id = ? and account_id = ? and expires_at = ?
                   and revoked_at is null and expires_at <= ?
                """, Timestamp.from(now), REFRESH_TOKEN_REASON, identity.familyId(), identity.accountId(),
                Timestamp.from(identity.expiresAt()), Timestamp.from(now));
        if (updated == 0) return RefreshTokenFamilyExpiryPort.Result.ALREADY_TRANSITIONED;

        String idempotencyKey = "refresh-token-family-expiry:" + identity.familyId() + ":" + identity.expiresAt();
        jdbc.update("""
                insert into identity.authentication_events
                    (id, account_id, event_sequence, event_type, actor_type, reason_code,
                     correlation_id, idempotency_key, occurred_at)
                values (?, ?,
                    (select coalesce(max(event_sequence), 0) + 1
                       from identity.authentication_events where account_id = ?),
                    'REFRESH_TOKEN_EXPIRED', 'SYSTEM', ?, ?, ?, ?)
                on conflict (account_id, idempotency_key) do nothing
                """, UUID.randomUUID(), identity.accountId(), identity.accountId(), REFRESH_TOKEN_REASON,
                correlationId, idempotencyKey, Timestamp.from(now));
        return RefreshTokenFamilyExpiryPort.Result.APPLIED;
    }

    private DelegatedCredentialExpiryPort.Result expireDelegatedCredential(
            DelegatedCredentialExpiryPort.Identity identity, UUID correlationId) {
        jdbc.queryForObject("select id from identity.delegated_authorizations where id = ? for update",
                UUID.class, identity.authorizationId());
        Instant now = databaseNow();
        return identity.kind() == Kind.CREDENTIAL
                ? expireCredentialOnly(identity, correlationId, now)
                : expireAuthorization(identity, correlationId, now);
    }

    private DelegatedCredentialExpiryPort.Result expireCredentialOnly(
            DelegatedCredentialExpiryPort.Identity identity, UUID correlationId, Instant now) {
        String idempotencyKey = "delegated-token-expiry:" + identity.credentialId() + ":" + identity.expiresAt();
        int inserted = jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id, reason_code,
                     correlation_id, idempotency_key, occurred_at)
                select ?, 'SYSTEM', ?, 'DELEGATED_CREDENTIAL_EXPIRED', 'DELEGATED_CREDENTIAL', ?,
                       ?, ?, ?, ?
                 where exists (
                    select 1
                      from identity.delegated_credentials c
                      join identity.delegated_authorizations a on a.id = c.authorization_id
                     where c.id = ? and c.authorization_id = ? and c.expires_at = ?
                       and c.revoked_at is null and c.expires_at <= ? and a.status = 'ACTIVE')
                on conflict (idempotency_key) do nothing
                """, UUID.randomUUID(), identity.authorizationId(), identity.credentialId(), DELEGATED_REASON,
                correlationId, idempotencyKey, Timestamp.from(now), identity.credentialId(),
                identity.authorizationId(), Timestamp.from(identity.expiresAt()), Timestamp.from(now));
        return inserted == 1
                ? DelegatedCredentialExpiryPort.Result.APPLIED
                : DelegatedCredentialExpiryPort.Result.ALREADY_TRANSITIONED;
    }

    private DelegatedCredentialExpiryPort.Result expireAuthorization(
            DelegatedCredentialExpiryPort.Identity identity, UUID correlationId, Instant now) {
        String idempotencyKey = "delegated-authorization-expiry:" + identity.authorizationId()
                + ":" + identity.expiresAt();
        int updated = jdbc.update("""
                update identity.delegated_authorizations
                   set status = 'EXPIRED'
                 where id = ? and status = 'ACTIVE' and expires_at = ? and expires_at <= ?
                """, identity.authorizationId(), Timestamp.from(identity.expiresAt()), Timestamp.from(now));
        if (updated == 0) return DelegatedCredentialExpiryPort.Result.ALREADY_TRANSITIONED;
        jdbc.update("""
                insert into identity.delegated_authorization_events
                    (id, authorization_id, event_sequence, event_type, actor_type, reason_code,
                     correlation_id, idempotency_key, occurred_at, payload_document)
                values (?, ?,
                    (select coalesce(max(event_sequence), 0) + 1
                       from identity.delegated_authorization_events where authorization_id = ?),
                    'EXPIRED', 'SYSTEM', 'DELEGATED_AUTHORIZATION_EXPIRED', ?, ?, ?, '{}'::jsonb)
                on conflict (authorization_id, idempotency_key) do nothing
                """, UUID.randomUUID(), identity.authorizationId(), identity.authorizationId(),
                correlationId, idempotencyKey, Timestamp.from(now));
        return DelegatedCredentialExpiryPort.Result.APPLIED;
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(
                jdbc.queryForObject("select clock_timestamp()", Timestamp.class)).toInstant();
    }

    private static void requireLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }
}
