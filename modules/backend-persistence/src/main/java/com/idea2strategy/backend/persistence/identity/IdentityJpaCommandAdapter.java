package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.AuthenticationSession;
import com.idea2strategy.backend.application.identity.AuthenticationSuccess;
import com.idea2strategy.backend.application.identity.IdentityCommandPort;
import com.idea2strategy.backend.application.identity.LoginFailure;
import com.idea2strategy.backend.application.identity.PendingRegistration;
import com.idea2strategy.backend.application.identity.RegistrationCommandPort;
import com.idea2strategy.backend.application.identity.VerificationOutcome;
import com.idea2strategy.backend.application.identity.VerificationReplacement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdentityJpaCommandAdapter implements RegistrationCommandPort, IdentityCommandPort {
    private final EntityManager entityManager;

    public IdentityJpaCommandAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void createPending(PendingRegistration registration) {
        OffsetDateTime now = utc(registration.requestedAt());
        entityManager.createNativeQuery("""
                        insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                        values (:id, cast('PENDING_VERIFICATION' as identity.account_lifecycle_status), :now, :now)
                        """)
                .setParameter("id", registration.accountId())
                .setParameter("now", now)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.account_security_states (account_id, auth_epoch, updated_at)
                        values (:accountId, 1, :now)
                        """)
                .setParameter("accountId", registration.accountId())
                .setParameter("now", now)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.account_emails
                            (account_id, email_ciphertext, email_lookup_hmac, email_lookup_key_version,
                             encryption_key_version, status, created_at)
                        values (:accountId, :ciphertext, :lookupHmac, :lookupVersion, :encryptionVersion,
                                cast('PENDING_VERIFICATION' as identity.email_status), :now)
                        """)
                .setParameter("accountId", registration.accountId())
                .setParameter("ciphertext", registration.email().ciphertext())
                .setParameter("lookupHmac", registration.email().lookupHmac())
                .setParameter("lookupVersion", registration.email().lookupKeyVersion())
                .setParameter("encryptionVersion", registration.email().encryptionKeyVersion())
                .setParameter("now", now)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.login_identities
                            (id, account_id, provider_id, status, created_at)
                        select :id, :accountId, id, cast('PENDING' as identity.login_identity_status), :now
                        from identity.auth_providers where code = 'PASSWORD' and is_active = true
                        """)
                .setParameter("id", registration.loginIdentityId())
                .setParameter("accountId", registration.accountId())
                .setParameter("now", now)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.password_credentials
                            (login_identity_id, password_hash, hash_scheme, hash_parameters,
                             credential_version, password_changed_at)
                        values (:loginId, :hash, :scheme, cast(:parameters as jsonb), 1, :now)
                        """)
                .setParameter("loginId", registration.loginIdentityId())
                .setParameter("hash", registration.password().encodedHash())
                .setParameter("scheme", registration.password().scheme())
                .setParameter("parameters", registration.password().parametersJson())
                .setParameter("now", now)
                .executeUpdate();
        insertVerification(
                registration.verificationRequestId(),
                registration.accountId(),
                registration.verificationTokenDigest(),
                registration.requestedAt(),
                registration.expiresAt(),
                registration.requestIpPrefix());
        insertLifecycleEvent(registration.accountId(), null, "PENDING_VERIFICATION", "SIGNUP_REQUESTED", now);
        insertAuthenticationEvent(
                registration.accountId(),
                "SIGNUP_REQUESTED",
                registration.loginIdentityId(),
                "USER",
                null,
                registration.correlationId(),
                "signup:" + registration.correlationId(),
                now);
    }

    @Override
    @Transactional
    public VerificationOutcome consumeVerification(String tokenDigest, Instant consumedAt, UUID correlationId) {
        Object[] row;
        try {
            row = (Object[]) entityManager.createNativeQuery("""
                            select request.id, request.account_id, request.expires_at, request.consumed_at,
                                   request.revoked_at, account.lifecycle_status::text, login.id
                            from identity.email_verification_requests request
                            join identity.accounts account on account.id = request.account_id
                            join identity.login_identities login on login.account_id = account.id
                            join identity.auth_providers provider on provider.id = login.provider_id
                            where request.token_digest = :digest and provider.code = 'PASSWORD'
                            for update of request, account, login
                            """)
                    .setParameter("digest", tokenDigest)
                    .getSingleResult();
        } catch (NoResultException exception) {
            return VerificationOutcome.NOT_FOUND;
        }

        UUID requestId = (UUID) row[0];
        UUID accountId = (UUID) row[1];
        Instant expiresAt = instant(row[2]);
        UUID loginId = (UUID) row[6];
        if (row[3] != null || row[4] != null) {
            return VerificationOutcome.ALREADY_USED;
        }
        if (!"PENDING_VERIFICATION".equals(row[5])) {
            return VerificationOutcome.ACCOUNT_NOT_PENDING;
        }
        OffsetDateTime now = utc(consumedAt);
        if (!expiresAt.isAfter(consumedAt)) {
            entityManager.createNativeQuery("""
                            update identity.email_verification_requests
                            set failed_attempt_count = failed_attempt_count + 1
                            where id = :id
                            """)
                    .setParameter("id", requestId)
                    .executeUpdate();
            return VerificationOutcome.EXPIRED;
        }

        entityManager.createNativeQuery("""
                        update identity.email_verification_requests set consumed_at = :now where id = :id
                        """)
                .setParameter("now", now)
                .setParameter("id", requestId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.account_emails
                        set status = cast('VERIFIED' as identity.email_status), verified_at = :now
                        where account_id = :accountId
                        """)
                .setParameter("now", now)
                .setParameter("accountId", accountId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set status = cast('ACTIVE' as identity.login_identity_status), linked_at = :now,
                            activated_at = :now
                        where id = :loginId and status = cast('PENDING' as identity.login_identity_status)
                        """)
                .setParameter("now", now)
                .setParameter("loginId", loginId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.accounts
                        set lifecycle_status = cast('ACTIVE' as identity.account_lifecycle_status),
                            status_changed_at = :now
                        where id = :accountId
                        """)
                .setParameter("now", now)
                .setParameter("accountId", accountId)
                .executeUpdate();
        insertLifecycleEvent(accountId, "PENDING_VERIFICATION", "ACTIVE", "EMAIL_VERIFIED", now);
        insertAuthenticationEvent(
                accountId,
                "EMAIL_VERIFIED",
                loginId,
                "USER",
                null,
                correlationId,
                "verify:" + correlationId,
                now);
        return VerificationOutcome.VERIFIED;
    }

    @Override
    @Transactional
    public void replaceVerification(VerificationReplacement replacement) {
        Object[] account = (Object[]) entityManager.createNativeQuery("""
                        select account.lifecycle_status::text, login.id
                        from identity.accounts account
                        join identity.login_identities login on login.account_id = account.id
                        join identity.auth_providers provider on provider.id = login.provider_id
                        where account.id = :accountId and provider.code = 'PASSWORD'
                        for update of account, login
                        """)
                .setParameter("accountId", replacement.accountId())
                .getSingleResult();
        if (!"PENDING_VERIFICATION".equals(account[0])) {
            throw new IllegalStateException("Only pending accounts can request another verification token");
        }
        OffsetDateTime now = utc(replacement.requestedAt());
        entityManager.createNativeQuery("""
                        update identity.email_verification_requests set revoked_at = :now
                        where account_id = :accountId and consumed_at is null and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", replacement.accountId())
                .executeUpdate();
        insertVerification(
                replacement.requestId(),
                replacement.accountId(),
                replacement.tokenDigest(),
                replacement.requestedAt(),
                replacement.expiresAt(),
                replacement.requestIpPrefix());
        insertAuthenticationEvent(
                replacement.accountId(),
                "EMAIL_VERIFICATION_REISSUED",
                (UUID) account[1],
                "USER",
                null,
                replacement.correlationId(),
                "verification-reissue:" + replacement.correlationId(),
                now);
    }

    @Override
    @Transactional
    public void createSession(AuthenticationSession session) {
        entityManager.createNativeQuery("""
                        insert into identity.sessions
                            (id, account_id, authenticated_by_login_identity_id, auth_epoch_at_issue,
                             credential_version_at_issue, token_digest, digest_key_version, device_label,
                             issued_at, last_seen_at, expires_at)
                        values (:id, :accountId, :loginId, :authEpoch, :credentialVersion, :digest, 1,
                                :deviceLabel, :issuedAt, :issuedAt, :expiresAt)
                        """)
                .setParameter("id", session.id())
                .setParameter("accountId", session.accountId())
                .setParameter("loginId", session.loginIdentityId())
                .setParameter("authEpoch", session.authEpoch())
                .setParameter("credentialVersion", session.credentialVersion())
                .setParameter("digest", session.tokenDigest())
                .setParameter("deviceLabel", session.deviceLabel())
                .setParameter("issuedAt", utc(session.issuedAt()))
                .setParameter("expiresAt", utc(session.expiresAt()))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void recordLoginFailure(LoginFailure failure) {
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set failed_attempt_count = failed_attempt_count + 1, last_failed_at = :now
                        where id = :loginId
                        """)
                .setParameter("now", utc(failure.occurredAt()))
                .setParameter("loginId", failure.loginIdentityId())
                .executeUpdate();
        insertAuthenticationEvent(
                failure.accountId(),
                "LOGIN_FAILED",
                failure.loginIdentityId(),
                "USER",
                failure.reasonCode(),
                failure.correlationId(),
                "login-failure:" + failure.correlationId(),
                utc(failure.occurredAt()));
    }

    @Override
    @Transactional
    public void recordLoginSuccess(AuthenticationSuccess success) {
        OffsetDateTime now = utc(success.occurredAt());
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set last_authenticated_at = :now, failed_attempt_count = 0
                        where id = :loginId
                        """)
                .setParameter("now", now)
                .setParameter("loginId", success.loginIdentityId())
                .executeUpdate();
        insertAuthenticationEvent(
                success.accountId(),
                "LOGIN_SUCCEEDED",
                success.loginIdentityId(),
                "USER",
                null,
                success.correlationId(),
                "login-success:" + success.correlationId(),
                now);
    }

    @Override
    @Transactional
    public void completeLogin(AuthenticationSession session, AuthenticationSuccess success) {
        createSession(session);
        recordLoginSuccess(success);
    }

    private void insertVerification(
            UUID requestId, UUID accountId, String digest, Instant requestedAt, Instant expiresAt, String ipPrefix) {
        entityManager.createNativeQuery("""
                        insert into identity.email_verification_requests
                            (id, account_id, token_digest, digest_key_version, requested_at, expires_at,
                             request_ip_prefix)
                        values (:id, :accountId, :digest, 1, :requestedAt, :expiresAt, cast(:ipPrefix as inet))
                        """)
                .setParameter("id", requestId)
                .setParameter("accountId", accountId)
                .setParameter("digest", digest)
                .setParameter("requestedAt", utc(requestedAt))
                .setParameter("expiresAt", utc(expiresAt))
                .setParameter("ipPrefix", ipPrefix)
                .executeUpdate();
    }

    private void insertLifecycleEvent(
            UUID accountId, String previousStatus, String newStatus, String reason, OffsetDateTime occurredAt) {
        entityManager.createNativeQuery("""
                        insert into identity.account_lifecycle_events
                            (id, account_id, event_sequence, previous_status, new_status, reason_code, occurred_at)
                        values (gen_random_uuid(), :accountId,
                                (select coalesce(max(event_sequence), 0) + 1
                                 from identity.account_lifecycle_events where account_id = :accountId),
                                cast(:previousStatus as identity.account_lifecycle_status),
                                cast(:newStatus as identity.account_lifecycle_status), :reason, :occurredAt)
                        """)
                .setParameter("accountId", accountId)
                .setParameter("previousStatus", previousStatus)
                .setParameter("newStatus", newStatus)
                .setParameter("reason", reason)
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();
    }

    private void insertAuthenticationEvent(
            UUID accountId,
            String eventType,
            UUID loginIdentityId,
            String actorType,
            String reason,
            UUID correlationId,
            String idempotencyKey,
            OffsetDateTime occurredAt) {
        entityManager.createNativeQuery("select id from identity.accounts where id = :id for update")
                .setParameter("id", accountId)
                .getSingleResult();
        entityManager.createNativeQuery("""
                        insert into identity.authentication_events
                            (id, account_id, event_sequence, event_type, subject_login_identity_id,
                             actor_type, reason_code, correlation_id, idempotency_key, occurred_at)
                        values (gen_random_uuid(), :accountId,
                                (select coalesce(max(event_sequence), 0) + 1
                                 from identity.authentication_events where account_id = :accountId),
                                :eventType, :loginId, :actorType, :reason, :correlationId, :idempotencyKey, :occurredAt)
                        """)
                .setParameter("accountId", accountId)
                .setParameter("eventType", eventType)
                .setParameter("loginId", loginIdentityId)
                .setParameter("actorType", actorType)
                .setParameter("reason", reason)
                .setParameter("correlationId", correlationId)
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }
}
