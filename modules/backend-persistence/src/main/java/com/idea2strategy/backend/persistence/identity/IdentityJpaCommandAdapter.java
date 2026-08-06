package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.AuthenticationSession;
import com.idea2strategy.backend.application.identity.AuthenticationSuccess;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.ActivateOidcLink;
import com.idea2strategy.backend.application.identity.AccountRecoveryCommandPort;
import com.idea2strategy.backend.application.identity.IdentityCommandPort;
import com.idea2strategy.backend.application.identity.IdentifierFingerprint;
import com.idea2strategy.backend.application.identity.IdentifierAdvisoryLockKey;
import com.idea2strategy.backend.application.identity.DuplicateEmailException;
import com.idea2strategy.backend.application.identity.LoginFailure;
import com.idea2strategy.backend.application.identity.OidcIdentityCommandPort;
import com.idea2strategy.backend.application.identity.PendingRegistration;
import com.idea2strategy.backend.application.identity.PendingOidcLink;
import com.idea2strategy.backend.application.identity.PendingOidcRegistration;
import com.idea2strategy.backend.application.identity.PendingPasswordReset;
import com.idea2strategy.backend.application.identity.PasswordResetConsumption;
import com.idea2strategy.backend.application.identity.PasswordResetOutcome;
import com.idea2strategy.backend.application.identity.RecoveryCodeBatch;
import com.idea2strategy.backend.application.identity.RecoveryCodeConsumption;
import com.idea2strategy.backend.application.identity.RecoveryCodeOutcome;
import com.idea2strategy.backend.application.identity.RegistrationCommandPort;
import com.idea2strategy.backend.application.identity.SessionCommandPort;
import com.idea2strategy.backend.application.identity.VerificationOutcome;
import com.idea2strategy.backend.application.identity.VerificationReplacement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdentityJpaCommandAdapter
        implements RegistrationCommandPort,
                IdentityCommandPort,
                OidcIdentityCommandPort,
                SessionCommandPort,
                AccountRecoveryCommandPort {
    private final EntityManager entityManager;

    public IdentityJpaCommandAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void createActiveRegistration(PendingOidcRegistration registration) {
        String providerCode = (String) entityManager.createNativeQuery(
                        "select code from identity.auth_providers where id = :providerId and is_active = true")
                .setParameter("providerId", registration.providerId())
                .getSingleResult();
        guardIdentifierReuse("EMAIL", "PASSWORD", registration.email().comparisonFingerprints(), null);
        guardIdentifierReuse("OIDC_SUBJECT", providerCode, registration.subject().comparisonFingerprints(), null);
        OffsetDateTime now = utc(registration.registeredAt());
        entityManager.createNativeQuery("""
                        insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                        values (:id, cast('ACTIVE' as identity.account_lifecycle_status), :now, :now)
                        """)
                .setParameter("id", registration.accountId())
                .setParameter("now", now)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.account_preferences
                            (account_id, language_code, timezone_name, theme_preference, created_at, updated_at)
                        values (:accountId, :languageCode, :timezoneName,
                                cast(:themePreference as identity.theme_preference), :now, :now)
                        """)
                .setParameter("accountId", registration.accountId())
                .setParameter("languageCode", registration.preferences().languageCode())
                .setParameter("timezoneName", registration.preferences().timezoneName())
                .setParameter("themePreference", registration.preferences().themePreference().name())
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
                             encryption_key_version, status, verified_at, created_at)
                        values (:accountId, :ciphertext, :lookupHmac, :lookupVersion, :encryptionVersion,
                                cast('VERIFIED' as identity.email_status), :now, :now)
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
                            (id, account_id, provider_id, provider_subject_hmac, subject_key_version,
                             status, created_at, linked_at, activated_at)
                        values (:id, :accountId, :providerId, :subjectHmac, :keyVersion,
                                cast('ACTIVE' as identity.login_identity_status), :now, :now, :now)
                        """)
                .setParameter("id", registration.loginIdentityId())
                .setParameter("accountId", registration.accountId())
                .setParameter("providerId", registration.providerId())
                .setParameter("subjectHmac", registration.subject().hmac())
                .setParameter("keyVersion", registration.subject().keyVersion())
                .setParameter("now", now)
                .executeUpdate();
        insertAuthenticationEvent(
                registration.accountId(),
                "OIDC_SIGNUP_COMPLETED",
                registration.loginIdentityId(),
                "USER",
                null,
                registration.correlationId(),
                "oidc-signup:" + registration.correlationId(),
                now);
    }

    @Override
    @Transactional
    public void createPending(PendingRegistration registration) {
        OffsetDateTime now = utc(registration.requestedAt());
        try {
            guardIdentifierReuse("EMAIL", "PASSWORD", registration.email().comparisonFingerprints(), null);
        } catch (AuthenticationRejectedException rejected) {
            throw new DuplicateEmailException();
        }
        entityManager.createNativeQuery("""
                        insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                        values (:id, cast('PENDING_VERIFICATION' as identity.account_lifecycle_status), :now, :now)
                        """)
                .setParameter("id", registration.accountId())
                .setParameter("now", now)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.account_preferences
                            (account_id, language_code, timezone_name, theme_preference,
                             created_at, updated_at)
                        values (:accountId, :languageCode, :timezoneName,
                                cast(:themePreference as identity.theme_preference),
                                :createdAt, :updatedAt)
                        """)
                .setParameter("accountId", registration.accountId())
                .setParameter("languageCode", registration.preferences().languageCode())
                .setParameter("timezoneName", registration.preferences().timezoneName())
                .setParameter("themePreference", registration.preferences().themePreference().name())
                .setParameter("createdAt", now)
                .setParameter("updatedAt", utc(registration.preferences().updatedAt()))
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
        transitionLifecycle(
                accountId,
                "PENDING_VERIFICATION",
                "ACTIVE",
                "EMAIL_VERIFIED",
                correlationId,
                "verify:" + correlationId,
                now);
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
        entityManager.createNativeQuery("""
                        update identity.accounts
                        set last_successful_auth_at = :now
                        where id = :accountId
                          and (last_successful_auth_at is null or last_successful_auth_at < :now)
                        """)
                .setParameter("now", now)
                .setParameter("accountId", success.accountId())
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
    public void recordStepUpSuccess(AuthenticationSuccess success) {
        OffsetDateTime now = utc(success.occurredAt());
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set last_authenticated_at = :now, failed_attempt_count = 0
                        where id = :loginId
                        """)
                .setParameter("now", now)
                .setParameter("loginId", success.loginIdentityId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.accounts
                        set last_successful_auth_at = :now
                        where id = :accountId
                          and (last_successful_auth_at is null or last_successful_auth_at < :now)
                        """)
                .setParameter("now", now)
                .setParameter("accountId", success.accountId())
                .executeUpdate();
        insertAuthenticationEvent(
                success.accountId(),
                "STEP_UP_SUCCEEDED",
                success.loginIdentityId(),
                "USER",
                null,
                success.correlationId(),
                "step-up-success:" + success.correlationId(),
                now);
    }

    @Override
    @Transactional
    public void completeLogin(AuthenticationSession session, AuthenticationSuccess success) {
        completeLogin(session, success, Integer.MAX_VALUE);
    }

    @Override
    @Transactional
    public void completeLogin(AuthenticationSession session, AuthenticationSuccess success, int maxActiveSessions) {
        entityManager.createNativeQuery("""
                        select account_id from identity.account_security_states
                        where account_id = :accountId for update
                        """)
                .setParameter("accountId", session.accountId())
                .getSingleResult();
        Number activeSessions = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.sessions
                        where account_id = :accountId and revoked_at is null and expires_at > :now
                        """)
                .setParameter("accountId", session.accountId())
                .setParameter("now", utc(session.issuedAt()))
                .getSingleResult();
        if (activeSessions.intValue() >= maxActiveSessions) {
            throw new AuthenticationRejectedException("Active session limit reached");
        }
        createSession(session);
        recordLoginSuccess(success);
        Number activeSanctions = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.account_sanctions
                        where account_id = :accountId
                          and status = cast('ACTIVE' as identity.sanction_status)
                          and sanction_type in ('SUSPENSION', 'PERMANENT')
                        """)
                .setParameter("accountId", session.accountId())
                .getSingleResult();
        if (activeSanctions.longValue() > 0) {
            insertAuthenticationEvent(
                    success.accountId(),
                    "SANCTIONED_LOGIN_SUCCEEDED",
                    success.loginIdentityId(),
                    "USER",
                    "ACTIVE_ACCOUNT_SANCTION",
                    success.correlationId(),
                    "sanctioned-login-success:" + success.correlationId(),
                    utc(success.occurredAt()));
        }
    }

    @Override
    @Transactional
    public boolean revoke(UUID accountId, UUID sessionId, String reason, UUID correlationId, Instant now) {
        UUID loginIdentityId;
        try {
            loginIdentityId = (UUID) entityManager.createNativeQuery("""
                            select authenticated_by_login_identity_id
                            from identity.sessions
                            where id = :sessionId and account_id = :accountId
                              and revoked_at is null and expires_at > :now
                            for update
                            """)
                    .setParameter("sessionId", sessionId)
                    .setParameter("accountId", accountId)
                    .setParameter("now", utc(now))
                    .getSingleResult();
        } catch (NoResultException exception) {
            return false;
        }
        entityManager.createNativeQuery("""
                        update identity.sessions
                        set revoked_at = :now, revoke_reason_code = :reason
                        where id = :sessionId and account_id = :accountId and revoked_at is null
                        """)
                .setParameter("now", utc(now))
                .setParameter("reason", reason)
                .setParameter("sessionId", sessionId)
                .setParameter("accountId", accountId)
                .executeUpdate();
        insertSessionEvent(accountId, loginIdentityId, sessionId, "SESSION_REVOKED", reason, correlationId, utc(now));
        return true;
    }

    @Override
    @Transactional
    public void touch(UUID accountId, UUID sessionId, Instant now) {
        entityManager.createNativeQuery("""
                        update identity.sessions set last_seen_at = :now
                        where id = :sessionId and account_id = :accountId
                          and revoked_at is null and expires_at > :now
                        """)
                .setParameter("now", utc(now))
                .setParameter("sessionId", sessionId)
                .setParameter("accountId", accountId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void recordEvent(
            UUID accountId,
            UUID loginIdentityId,
            UUID sessionId,
            String eventType,
            String reason,
            UUID correlationId,
            Instant now) {
        insertSessionEvent(accountId, loginIdentityId, sessionId, eventType, reason, correlationId, utc(now));
    }

    @Override
    @Transactional
    public boolean rotate(
            UUID accountId,
            UUID sessionId,
            String previousTokenDigest,
            String replacementTokenDigest,
            Instant expiresAt,
            UUID correlationId,
            Instant now) {
        int updated = entityManager.createNativeQuery("""
                        update identity.sessions
                        set token_digest = :replacementDigest, digest_key_version = 1,
                            issued_at = :now, last_seen_at = :now, expires_at = :expiresAt
                        where id = :sessionId and account_id = :accountId
                          and token_digest = :previousDigest and revoked_at is null and expires_at > :now
                        """)
                .setParameter("replacementDigest", replacementTokenDigest)
                .setParameter("previousDigest", previousTokenDigest)
                .setParameter("now", utc(now))
                .setParameter("expiresAt", utc(expiresAt))
                .setParameter("sessionId", sessionId)
                .setParameter("accountId", accountId)
                .executeUpdate();
        if (updated == 0) {
            return false;
        }
        insertSessionEvent(accountId, null, sessionId, "SESSION_ROTATED", null, correlationId, utc(now));
        return true;
    }

    @Override
    @Transactional
    public int revokeAll(UUID accountId, String reason, UUID correlationId, Instant now) {
        entityManager.createNativeQuery("select id from identity.accounts where id = :id for update")
                .setParameter("id", accountId)
                .getSingleResult();
        entityManager.createNativeQuery("""
                        update identity.account_security_states
                        set auth_epoch = auth_epoch + 1, sessions_revoked_before = :now, updated_at = :now
                        where account_id = :accountId
                        """)
                .setParameter("now", utc(now))
                .setParameter("accountId", accountId)
                .executeUpdate();
        int count = entityManager.createNativeQuery("""
                        update identity.sessions
                        set revoked_at = :now, revoke_reason_code = :reason
                        where account_id = :accountId and revoked_at is null and expires_at > :now
                        """)
                .setParameter("now", utc(now))
                .setParameter("reason", reason)
                .setParameter("accountId", accountId)
                .executeUpdate();
        insertSessionEvent(accountId, null, null, "SESSIONS_REVOKED", reason, correlationId, utc(now));
        return count;
    }

    @Override
    @Transactional
    public void createPendingLink(PendingOidcLink link) {
        String providerCode = (String) entityManager.createNativeQuery(
                        "select code from identity.auth_providers where id = :providerId")
                .setParameter("providerId", link.providerId()).getSingleResult();
        guardIdentifierReuse("OIDC_SUBJECT", providerCode, link.comparisonFingerprints(), null);
        Object status = entityManager.createNativeQuery("""
                        select account.lifecycle_status::text
                        from identity.accounts account
                        join identity.login_identities login on login.account_id = account.id
                        join identity.auth_providers provider on provider.id = :providerId
                        where account.id = :accountId and login.id = :currentLoginId
                          and login.status = cast('ACTIVE' as identity.login_identity_status)
                          and provider.provider_type = cast('OIDC' as identity.auth_provider_type)
                          and provider.is_active = true
                        for update of account, login, provider
                        """)
                .setParameter("accountId", link.accountId())
                .setParameter("currentLoginId", link.reauthenticatedLoginIdentityId())
                .setParameter("providerId", link.providerId())
                .getSingleResult();
        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException("Only active accounts can link an OIDC identity");
        }
        OffsetDateTime now = utc(link.requestedAt());
        entityManager.createNativeQuery("""
                        insert into identity.login_identities
                            (id, account_id, provider_id, provider_subject_hmac, subject_key_version,
                             status, created_at, linked_at)
                        values (:id, :accountId, :providerId, :subjectHmac, :keyVersion,
                                cast('PENDING' as identity.login_identity_status), :now, :now)
                        """)
                .setParameter("id", link.id())
                .setParameter("accountId", link.accountId())
                .setParameter("providerId", link.providerId())
                .setParameter("subjectHmac", link.subjectHmac())
                .setParameter("keyVersion", link.subjectKeyVersion())
                .setParameter("now", now)
                .executeUpdate();
        insertAuthenticationEvent(
                link.accountId(),
                "OIDC_LINK_PENDING",
                link.id(),
                "USER",
                null,
                link.correlationId(),
                "oidc-link-pending:" + link.correlationId(),
                now);
    }

    @Override
    @Transactional
    public long activatePendingLink(ActivateOidcLink command) {
        String providerCode = (String) entityManager.createNativeQuery(
                        "select code from identity.auth_providers where id = :providerId")
                .setParameter("providerId", command.providerId()).getSingleResult();
        List<IdentifierFingerprint> comparisonFingerprints = command.comparisonFingerprints().isEmpty()
                ? List.of(new IdentifierFingerprint(command.subjectHmac(), pendingSubjectKeyVersion(
                        command.pendingLoginIdentityId())))
                : command.comparisonFingerprints();
        guardIdentifierReuse("OIDC_SUBJECT", providerCode, comparisonFingerprints,
                command.pendingLoginIdentityId());
        Object[] row;
        try {
            row = (Object[]) entityManager.createNativeQuery("""
                            select current_login.status::text, pending_login.status::text, security.auth_epoch
                            from identity.accounts account
                            join identity.account_security_states security on security.account_id = account.id
                            join identity.login_identities current_login
                              on current_login.account_id = account.id and current_login.id = :currentLoginId
                            join identity.login_identities pending_login
                              on pending_login.account_id = account.id and pending_login.id = :pendingLoginId
                            where account.id = :accountId
                              and pending_login.provider_id = :providerId
                              and pending_login.provider_subject_hmac = :subjectHmac
                            for update of account, security, current_login, pending_login
                            """)
                    .setParameter("accountId", command.accountId())
                    .setParameter("currentLoginId", command.reauthenticatedLoginIdentityId())
                    .setParameter("pendingLoginId", command.pendingLoginIdentityId())
                    .setParameter("providerId", command.providerId())
                    .setParameter("subjectHmac", command.subjectHmac())
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new AuthenticationRejectedException("OIDC link is not valid for this account");
        }
        if (!"ACTIVE".equals(row[0]) || !"PENDING".equals(row[1])) {
            throw new AuthenticationRejectedException("OIDC link is no longer activatable");
        }

        OffsetDateTime now = utc(command.activatedAt());
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set status = cast('REPLACED' as identity.login_identity_status), replaced_at = :now
                        where id = :currentLoginId
                        """)
                .setParameter("now", now)
                .setParameter("currentLoginId", command.reauthenticatedLoginIdentityId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.login_identities
                        set status = cast('ACTIVE' as identity.login_identity_status), activated_at = :now
                        where id = :pendingLoginId
                        """)
                .setParameter("now", now)
                .setParameter("pendingLoginId", command.pendingLoginIdentityId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.account_security_states
                        set auth_epoch = auth_epoch + 1, sessions_revoked_before = :now, updated_at = :now
                        where account_id = :accountId
                        """)
                .setParameter("now", now)
                .setParameter("accountId", command.accountId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.sessions
                        set revoked_at = coalesce(revoked_at, :now),
                            revoke_reason_code = coalesce(revoke_reason_code, 'LOGIN_IDENTITY_REPLACED')
                        where account_id = :accountId and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", command.accountId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.password_reset_requests
                        set revoked_at = :now
                        where account_id = :accountId and consumed_at is null and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", command.accountId())
                .executeUpdate();
        insertAuthenticationTransitionEvent(command, now);
        return ((Number) row[2]).longValue() + 1;
    }

    private short pendingSubjectKeyVersion(UUID pendingLoginIdentityId) {
        return ((Number) entityManager.createNativeQuery(
                        "select subject_key_version from identity.login_identities where id = :id")
                .setParameter("id", pendingLoginIdentityId).getSingleResult()).shortValue();
    }

    private void guardIdentifierReuse(String kind, String providerCode,
                                      List<IdentifierFingerprint> fingerprints, UUID excludedLoginId) {
        if (fingerprints.isEmpty()) {
            throw new AuthenticationRejectedException("Identifier comparison key ring is unavailable");
        }
        var ordered = fingerprints.stream()
                .sorted(java.util.Comparator.comparingInt(IdentifierFingerprint::keyVersion)
                        .thenComparing(IdentifierFingerprint::value))
                .toList();
        if (ordered.stream().map(IdentifierFingerprint::keyVersion).distinct().count() != ordered.size()) {
            throw new AuthenticationRejectedException("Identifier comparison key versions are ambiguous");
        }
        for (var fingerprint : ordered) {
            entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .setParameter("key", IdentifierAdvisoryLockKey.of(kind, providerCode, fingerprint))
                    .getSingleResult();
        }

        @SuppressWarnings("unchecked")
        List<Number> requiredKeyVersions = entityManager.createNativeQuery("""
                        select distinct required.key_version
                        from (
                            select quarantine.fingerprint_key_version key_version
                            from identity.account_identifier_quarantines quarantine
                            where quarantine.identifier_kind = :kind
                              and quarantine.provider_code = :providerCode
                              and quarantine.released_at is null
                            union all
                            select email.email_lookup_key_version
                            from identity.account_emails email
                            where :kind = 'EMAIL' and :providerCode = 'PASSWORD'
                              and email.email_lookup_hmac is not null
                            union all
                            select login.subject_key_version
                            from identity.login_identities login
                            join identity.auth_providers provider on provider.id = login.provider_id
                            where :kind = 'OIDC_SUBJECT' and provider.code = :providerCode
                              and login.provider_subject_hmac is not null
                        ) required
                        where required.key_version is not null
                        """).setParameter("kind", kind).setParameter("providerCode", providerCode).getResultList();
        if (requiredKeyVersions.stream().map(Number::shortValue)
                .anyMatch(version -> ordered.stream().noneMatch(candidate -> candidate.keyVersion() == version))) {
            throw new AuthenticationRejectedException("Identifier comparison key ring is incomplete");
        }

        for (var fingerprint : ordered) {
            Number quarantineCount = (Number) entityManager.createNativeQuery("""
                            select count(*) from identity.account_identifier_quarantines
                            where identifier_kind = :kind and provider_code = :providerCode
                              and identifier_fingerprint = :fingerprint
                              and fingerprint_key_version = :keyVersion and released_at is null
                            """).setParameter("kind", kind).setParameter("providerCode", providerCode)
                    .setParameter("fingerprint", fingerprint.value())
                    .setParameter("keyVersion", fingerprint.keyVersion()).getSingleResult();
            if (quarantineCount.intValue() != 0) {
                throw new AuthenticationRejectedException("Identifier is quarantined");
            }
            Number bindingCount;
            if ("EMAIL".equals(kind)) {
                bindingCount = (Number) entityManager.createNativeQuery("""
                                select count(*) from identity.account_emails
                                where email_lookup_hmac = :fingerprint
                                  and email_lookup_key_version = :keyVersion
                                """).setParameter("fingerprint", fingerprint.value())
                        .setParameter("keyVersion", fingerprint.keyVersion()).getSingleResult();
            } else {
                bindingCount = (Number) entityManager.createNativeQuery("""
                                select count(*) from identity.login_identities login
                                join identity.auth_providers provider on provider.id = login.provider_id
                                where provider.code = :providerCode
                                  and login.provider_subject_hmac = :fingerprint
                                  and login.subject_key_version = :keyVersion
                                  and (cast(:excludedId as uuid) is null or login.id <> cast(:excludedId as uuid))
                                """).setParameter("providerCode", providerCode)
                        .setParameter("fingerprint", fingerprint.value())
                        .setParameter("keyVersion", fingerprint.keyVersion())
                        .setParameter("excludedId", excludedLoginId).getSingleResult();
            }
            if (bindingCount.intValue() != 0) {
                throw new AuthenticationRejectedException("Identifier is already bound");
            }
        }
    }

    @Override
    @Transactional
    public void issuePasswordReset(PendingPasswordReset reset) {
        entityManager.createNativeQuery("select account_id from identity.account_security_states where account_id = :accountId for update")
                .setParameter("accountId", reset.accountId())
                .getSingleResult();
        OffsetDateTime now = utc(reset.requestedAt());
        entityManager.createNativeQuery("""
                        update identity.password_reset_requests set revoked_at = :now
                        where account_id = :accountId and consumed_at is null and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", reset.accountId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.password_reset_requests
                            (id, account_id, login_identity_id, auth_epoch_at_issue,
                             credential_version_at_issue, token_digest, digest_key_version,
                             requested_at, expires_at, request_ip_prefix)
                        values (:id, :accountId, :loginId, :authEpoch, :credentialVersion,
                                :digest, 1, :requestedAt, :expiresAt, cast(:ipPrefix as inet))
                        """)
                .setParameter("id", reset.id())
                .setParameter("accountId", reset.accountId())
                .setParameter("loginId", reset.loginIdentityId())
                .setParameter("authEpoch", reset.authEpoch())
                .setParameter("credentialVersion", reset.credentialVersion())
                .setParameter("digest", reset.tokenDigest())
                .setParameter("requestedAt", now)
                .setParameter("expiresAt", utc(reset.expiresAt()))
                .setParameter("ipPrefix", reset.requestIpPrefix())
                .executeUpdate();
        insertAuthenticationEvent(
                reset.accountId(),
                "PASSWORD_RESET_REQUESTED",
                reset.loginIdentityId(),
                "ACCOUNT",
                null,
                reset.correlationId(),
                "password-reset-request:" + reset.correlationId(),
                now);
    }

    @Override
    @Transactional
    public PasswordResetOutcome consumePasswordReset(PasswordResetConsumption consumption) {
        Object[] row;
        try {
            row = (Object[]) entityManager.createNativeQuery("""
                            select request.id, request.account_id, request.login_identity_id,
                                   request.auth_epoch_at_issue, request.credential_version_at_issue,
                                   request.expires_at, request.consumed_at, request.revoked_at,
                                   security.auth_epoch, credential.credential_version
                            from identity.password_reset_requests request
                            join identity.account_security_states security on security.account_id = request.account_id
                            join identity.password_credentials credential
                              on credential.login_identity_id = request.login_identity_id
                            where request.token_digest = :digest
                            for update of request, security, credential
                            """)
                    .setParameter("digest", consumption.tokenDigest())
                    .getSingleResult();
        } catch (NoResultException exception) {
            return PasswordResetOutcome.NOT_FOUND;
        }

        UUID requestId = (UUID) row[0];
        UUID accountId = (UUID) row[1];
        UUID loginId = (UUID) row[2];
        OffsetDateTime now = utc(consumption.consumedAt());
        if (row[6] != null || row[7] != null) {
            recordPasswordResetRejection(accountId, loginId, consumption, "ALREADY_USED", now);
            return PasswordResetOutcome.ALREADY_USED;
        }
        if (!instant(row[5]).isAfter(consumption.consumedAt())) {
            entityManager.createNativeQuery("update identity.password_reset_requests set failed_attempt_count = failed_attempt_count + 1 where id = :id")
                    .setParameter("id", requestId)
                    .executeUpdate();
            recordPasswordResetRejection(accountId, loginId, consumption, "EXPIRED", now);
            return PasswordResetOutcome.EXPIRED;
        }
        if (((Number) row[3]).longValue() != ((Number) row[8]).longValue()
                || ((Number) row[4]).longValue() != ((Number) row[9]).longValue()) {
            entityManager.createNativeQuery("update identity.password_reset_requests set revoked_at = :now where id = :id")
                    .setParameter("now", now)
                    .setParameter("id", requestId)
                    .executeUpdate();
            recordPasswordResetRejection(accountId, loginId, consumption, "STALE_CREDENTIAL", now);
            return PasswordResetOutcome.STALE;
        }

        entityManager.createNativeQuery("""
                        update identity.password_credentials
                        set password_hash = :hash, hash_scheme = :scheme,
                            hash_parameters = cast(:parameters as jsonb),
                            credential_version = credential_version + 1,
                            password_changed_at = :now, compromised_at = null
                        where login_identity_id = :loginId
                        """)
                .setParameter("hash", consumption.passwordHash().encodedHash())
                .setParameter("scheme", consumption.passwordHash().scheme())
                .setParameter("parameters", consumption.passwordHash().parametersJson())
                .setParameter("now", now)
                .setParameter("loginId", loginId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.account_security_states
                        set auth_epoch = auth_epoch + 1, sessions_revoked_before = :now, updated_at = :now
                        where account_id = :accountId
                        """)
                .setParameter("now", now)
                .setParameter("accountId", accountId)
                .executeUpdate();
        entityManager.createNativeQuery("update identity.password_reset_requests set consumed_at = :now where id = :id")
                .setParameter("now", now)
                .setParameter("id", requestId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.password_reset_requests set revoked_at = :now
                        where account_id = :accountId and id <> :id
                          and consumed_at is null and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", accountId)
                .setParameter("id", requestId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.sessions
                        set revoked_at = :now, revoke_reason_code = 'PASSWORD_RESET'
                        where account_id = :accountId and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", accountId)
                .executeUpdate();
        insertAuthenticationEvent(
                accountId,
                "PASSWORD_CHANGED",
                loginId,
                "ACCOUNT",
                "PASSWORD_RESET",
                consumption.correlationId(),
                "password-reset-consumed:" + requestId,
                now);
        insertSecurityNotification(accountId, "PASSWORD_CHANGED", consumption.correlationId(), now);
        return PasswordResetOutcome.CHANGED;
    }

    @Override
    @Transactional
    public void replaceRecoveryCodes(RecoveryCodeBatch batch) {
        entityManager.createNativeQuery("select account_id from identity.account_security_states where account_id = :accountId for update")
                .setParameter("accountId", batch.accountId())
                .getSingleResult();
        OffsetDateTime now = utc(batch.issuedAt());
        entityManager.createNativeQuery("""
                        update identity.recovery_code_sets
                        set revoked_at = :now, revoke_reason_code = 'REISSUED'
                        where account_id = :accountId and purpose = 'ACCOUNT_RECOVERY' and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", batch.accountId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        insert into identity.recovery_code_sets (id, account_id, purpose, issued_at)
                        values (:id, :accountId, 'ACCOUNT_RECOVERY', :now)
                        """)
                .setParameter("id", batch.id())
                .setParameter("accountId", batch.accountId())
                .setParameter("now", now)
                .executeUpdate();
        for (var code : batch.codes()) {
            entityManager.createNativeQuery("""
                            insert into identity.recovery_codes
                                (id, recovery_code_set_id, code_digest, digest_key_version)
                            values (:id, :setId, :digest, 1)
                            """)
                    .setParameter("id", code.id())
                    .setParameter("setId", batch.id())
                    .setParameter("digest", code.digest())
                    .executeUpdate();
        }
        insertAuthenticationEvent(
                batch.accountId(),
                "RECOVERY_CODES_ISSUED",
                null,
                "ACCOUNT",
                null,
                batch.correlationId(),
                "recovery-codes-issued:" + batch.id(),
                now);
        insertSecurityNotification(batch.accountId(), "RECOVERY_CODES_ISSUED", batch.correlationId(), now);
    }

    @Override
    @Transactional
    public RecoveryCodeOutcome consumeRecoveryCode(RecoveryCodeConsumption consumption) {
        Object[] row;
        try {
            row = (Object[]) entityManager.createNativeQuery("""
                            select code.id, code.used_at, code_set.revoked_at, login.id
                            from identity.recovery_codes code
                            join identity.recovery_code_sets code_set on code_set.id = code.recovery_code_set_id
                            join identity.account_security_states security on security.account_id = code_set.account_id
                            join identity.login_identities login on login.account_id = code_set.account_id
                            join identity.auth_providers provider on provider.id = login.provider_id
                            join identity.password_credentials credential on credential.login_identity_id = login.id
                            where code_set.account_id = :accountId and code.code_digest = :digest
                              and code_set.purpose = 'ACCOUNT_RECOVERY' and provider.code = 'PASSWORD'
                            for update of code, code_set, security, credential
                            """)
                    .setParameter("accountId", consumption.accountId())
                    .setParameter("digest", consumption.codeDigest())
                    .getSingleResult();
        } catch (NoResultException exception) {
            return RecoveryCodeOutcome.NOT_FOUND;
        }
        UUID codeId = (UUID) row[0];
        UUID loginId = (UUID) row[3];
        OffsetDateTime now = utc(consumption.consumedAt());
        if (row[2] != null) {
            recordRecoveryCodeRejection(consumption, loginId, "REVOKED", now);
            return RecoveryCodeOutcome.REVOKED;
        }
        if (row[1] != null) {
            recordRecoveryCodeRejection(consumption, loginId, "ALREADY_USED", now);
            return RecoveryCodeOutcome.ALREADY_USED;
        }
        entityManager.createNativeQuery("update identity.recovery_codes set used_at = :now where id = :id")
                .setParameter("now", now)
                .setParameter("id", codeId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.password_credentials
                        set password_hash = :hash, hash_scheme = :scheme,
                            hash_parameters = cast(:parameters as jsonb),
                            credential_version = credential_version + 1,
                            password_changed_at = :now, compromised_at = null
                        where login_identity_id = :loginId
                        """)
                .setParameter("hash", consumption.passwordHash().encodedHash())
                .setParameter("scheme", consumption.passwordHash().scheme())
                .setParameter("parameters", consumption.passwordHash().parametersJson())
                .setParameter("now", now)
                .setParameter("loginId", loginId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.account_security_states
                        set auth_epoch = auth_epoch + 1, sessions_revoked_before = :now, updated_at = :now
                        where account_id = :accountId
                        """)
                .setParameter("now", now)
                .setParameter("accountId", consumption.accountId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.sessions set revoked_at = :now, revoke_reason_code = 'RECOVERY_CODE_USED'
                        where account_id = :accountId and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", consumption.accountId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.password_reset_requests set revoked_at = :now
                        where account_id = :accountId and consumed_at is null and revoked_at is null
                        """)
                .setParameter("now", now)
                .setParameter("accountId", consumption.accountId())
                .executeUpdate();
        insertAuthenticationEvent(
                consumption.accountId(),
                "ACCOUNT_RECOVERED",
                loginId,
                "ACCOUNT",
                "RECOVERY_CODE",
                consumption.correlationId(),
                "recovery-code-consumed:" + codeId,
                now);
        insertSecurityNotification(consumption.accountId(), "ACCOUNT_RECOVERED", consumption.correlationId(), now);
        return RecoveryCodeOutcome.RECOVERED;
    }

    @Override
    @Transactional
    public void recordOidcRecoveryProof(
            UUID accountId, UUID loginIdentityId, UUID correlationId, Instant verifiedAt) {
        OffsetDateTime now = utc(verifiedAt);
        Number linked = (Number) entityManager.createNativeQuery("""
                        select count(*)
                        from identity.login_identities login
                        join identity.auth_providers provider on provider.id = login.provider_id
                        where login.id = :loginId and login.account_id = :accountId
                          and login.status = cast('ACTIVE' as identity.login_identity_status)
                          and provider.provider_type = cast('OIDC' as identity.auth_provider_type)
                          and provider.is_active = true
                        """)
                .setParameter("loginId", loginIdentityId)
                .setParameter("accountId", accountId)
                .getSingleResult();
        if (linked.intValue() != 1) {
            throw new AuthenticationRejectedException("OIDC identity is not linked");
        }
        insertAuthenticationEvent(
                accountId,
                "ACCOUNT_RECOVERY_PROOF_VERIFIED",
                loginIdentityId,
                "ACCOUNT",
                "LINKED_OIDC",
                correlationId,
                "oidc-recovery-proof:" + correlationId,
                now);
        insertSecurityNotification(accountId, "ACCOUNT_RECOVERY_PROOF_VERIFIED", correlationId, now);
    }

    private void recordRecoveryCodeRejection(
            RecoveryCodeConsumption consumption, UUID loginId, String reason, OffsetDateTime now) {
        insertAuthenticationEvent(
                consumption.accountId(),
                "ACCOUNT_RECOVERY_REJECTED",
                loginId,
                "ACCOUNT",
                reason,
                consumption.correlationId(),
                "recovery-code-rejected:" + consumption.correlationId(),
                now);
    }

    private void recordPasswordResetRejection(
            UUID accountId,
            UUID loginId,
            PasswordResetConsumption consumption,
            String reason,
            OffsetDateTime now) {
        insertAuthenticationEvent(
                accountId,
                "PASSWORD_RESET_REJECTED",
                loginId,
                "ACCOUNT",
                reason,
                consumption.correlationId(),
                "password-reset-rejected:" + consumption.correlationId(),
                now);
    }

    private void insertSecurityNotification(
            UUID accountId, String notificationType, UUID correlationId, OffsetDateTime now) {
        entityManager.createNativeQuery("""
                        insert into operations.notifications
                            (id, account_id, notification_type, mandatory, locale, template_version,
                             payload_document, idempotency_key, created_at)
                        values (gen_random_uuid(), :accountId, :type, true, 'en-US', 'v1',
                                jsonb_build_object('correlationId', cast(:correlationId as text)),
                                :idempotencyKey, :now)
                        on conflict (idempotency_key) do nothing
                        """)
                .setParameter("accountId", accountId)
                .setParameter("type", notificationType)
                .setParameter("correlationId", correlationId)
                .setParameter("idempotencyKey", "security:" + notificationType + ":" + correlationId)
                .setParameter("now", now)
                .executeUpdate();
    }

    private void insertAuthenticationTransitionEvent(ActivateOidcLink command, OffsetDateTime occurredAt) {
        entityManager.createNativeQuery("""
                        insert into identity.authentication_events
                            (id, account_id, event_sequence, event_type, subject_login_identity_id,
                             previous_login_identity_id, new_login_identity_id, actor_type,
                             correlation_id, idempotency_key, occurred_at)
                        values (gen_random_uuid(), :accountId,
                                (select coalesce(max(event_sequence), 0) + 1
                                 from identity.authentication_events where account_id = :accountId),
                                'LOGIN_IDENTITY_REPLACED', :newLoginId, :previousLoginId, :newLoginId,
                                'USER', :correlationId, :idempotencyKey, :occurredAt)
                        """)
                .setParameter("accountId", command.accountId())
                .setParameter("newLoginId", command.pendingLoginIdentityId())
                .setParameter("previousLoginId", command.reauthenticatedLoginIdentityId())
                .setParameter("correlationId", command.correlationId())
                .setParameter("idempotencyKey", "oidc-link-activate:" + command.correlationId())
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();
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

    private void transitionLifecycle(
            UUID accountId,
            String previousStatus,
            String newStatus,
            String reason,
            UUID correlationId,
            String idempotencyKey,
            OffsetDateTime occurredAt) {
        UUID eventId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                        insert into identity.account_lifecycle_events
                            (id, account_id, event_sequence, previous_event_id, lifecycle_version,
                             previous_status, new_status, command_type, actor_type, actor_id,
                             correlation_id, idempotency_key, request_hash, reason_code, occurred_at)
                        select :eventId, account.id, account.lifecycle_version + 1,
                               account.last_lifecycle_event_id, account.lifecycle_version + 1,
                               cast(:previousStatus as identity.account_lifecycle_status),
                               cast(:newStatus as identity.account_lifecycle_status), :reason,
                               'ACCOUNT', account.id::text, :correlationId, :idempotencyKey,
                               md5(:idempotencyKey) || md5(:idempotencyKey || ':2'), :reason, :occurredAt
                        from identity.accounts account
                        where account.id = :accountId
                        """)
                .setParameter("eventId", eventId)
                .setParameter("accountId", accountId)
                .setParameter("previousStatus", previousStatus)
                .setParameter("newStatus", newStatus)
                .setParameter("reason", reason)
                .setParameter("correlationId", correlationId)
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        update identity.accounts
                        set lifecycle_status = cast(:newStatus as identity.account_lifecycle_status),
                            status_changed_at = :occurredAt,
                            lifecycle_version = lifecycle_version + 1,
                            last_lifecycle_event_id = :eventId
                        where id = :accountId
                          and lifecycle_status = cast(:previousStatus as identity.account_lifecycle_status)
                        """)
                .setParameter("newStatus", newStatus)
                .setParameter("occurredAt", occurredAt)
                .setParameter("eventId", eventId)
                .setParameter("accountId", accountId)
                .setParameter("previousStatus", previousStatus)
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

    private void insertSessionEvent(
            UUID accountId,
            UUID loginIdentityId,
            UUID sessionId,
            String eventType,
            String reason,
            UUID correlationId,
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
                                :eventType, :loginId, 'ACCOUNT', :reason, :correlationId,
                                :idempotencyKey, :occurredAt)
                        on conflict (account_id, idempotency_key) do nothing
                        """)
                .setParameter("accountId", accountId)
                .setParameter("loginId", loginIdentityId)
                .setParameter("eventType", eventType)
                .setParameter("reason", reason)
                .setParameter("correlationId", correlationId)
                .setParameter("idempotencyKey", "session:" + eventType + ":" + sessionId + ":" + correlationId)
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
