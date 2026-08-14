package com.idea2strategy.backend.operatortrust;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class OperatorSessionService {
    private static final UUID PRE_AUTH_ACTOR = new UUID(0, 0);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OperatorPasswordHasher passwords;
    private final OperatorTotp totp;
    private final OperatorSecretCipher secrets;
    private final OperatorTokenProtector tokens;
    private final OperatorLoginThrottle throttle;
    private final Clock clock;
    private final Duration idleLifetime;
    private final Duration absoluteLifetime;
    private final String dummyHash;

    public OperatorSessionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            OperatorPasswordHasher passwords,
            OperatorTotp totp,
            OperatorSecretCipher secrets,
            OperatorTokenProtector tokens,
            Clock clock,
            Duration idleLifetime,
            Duration absoluteLifetime) {
        this(jdbc, transactionManager, passwords, totp, secrets, tokens,
                new InMemoryOperatorLoginThrottle(clock, Duration.ofMinutes(5), 10, 60),
                clock, idleLifetime, absoluteLifetime);
    }

    public OperatorSessionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            OperatorPasswordHasher passwords,
            OperatorTotp totp,
            OperatorSecretCipher secrets,
            OperatorTokenProtector tokens,
            OperatorLoginThrottle throttle,
            Clock clock,
            Duration idleLifetime,
            Duration absoluteLifetime) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.passwords = passwords;
        this.totp = totp;
        this.secrets = secrets;
        this.tokens = tokens;
        this.throttle = throttle;
        this.clock = clock;
        this.idleLifetime = idleLifetime;
        this.absoluteLifetime = absoluteLifetime;
        this.dummyHash = passwords.hash("operator-dummy-verification-value".toCharArray());
    }

    public IssuedSession login(String loginName, char[] password, String code, String source) {
        try {
            return transactions.execute(status -> loginInTransaction(normalize(loginName), password, code, source));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private IssuedSession loginInTransaction(String loginName, char[] password, String code, String source) {
        var loginDigest = tokens.digest(OperatorTokenProtector.Domain.LOGIN, loginName);
        var sourceDigest = tokens.digest(OperatorTokenProtector.Domain.SOURCE, source == null ? "unknown" : source);
        if (!throttle.acquire(loginDigest.hexDigest(), sourceDigest.hexDigest())) {
            audit(PRE_AUTH_ACTOR, "OPERATOR_LOGIN_RATE_LIMITED", "RATE_LIMITED", 429,
                    loginDigest.hexDigest(), sourceDigest.hexDigest());
            throw new OperatorAuthenticationRejectedException("OPERATOR_AUTHENTICATION_RATE_LIMITED");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.id, a.status, c.password_hash, c.credential_version, c.totp_ciphertext,
                       c.totp_nonce, c.totp_key_version, c.last_accepted_totp_step,
                       c.failed_attempt_count, c.locked_until, c.compromised_at
                from operations.operator_login_credentials c
                join operations.operator_accounts a on a.id=c.operator_account_id
                where c.login_name=? for update of c, a
                """, loginName);
        Instant now = clock.instant();
        if (rows.size() != 1) {
            passwords.verify(password, dummyHash);
            audit(PRE_AUTH_ACTOR, "OPERATOR_LOGIN_REJECTED", "AUTHENTICATION_REJECTED", 401,
                    loginDigest.hexDigest(), sourceDigest.hexDigest());
            throw rejected();
        }
        Map<String, Object> row = rows.getFirst();
        UUID operatorId = (UUID) row.get("id");
        boolean eligible = "ACTIVE".equals(row.get("status"))
                && row.get("compromised_at") == null
                && (row.get("locked_until") == null || !((Timestamp) row.get("locked_until")).toInstant().isAfter(now));
        boolean passwordValid = passwords.verify(password, row.get("password_hash").toString());
        long credentialVersion = ((Number) row.get("credential_version")).longValue();
        long lastStep = row.get("last_accepted_totp_step") == null
                ? -1 : ((Number) row.get("last_accepted_totp_step")).longValue();
        var encrypted = new OperatorSecretCipher.EncryptedSecret(
                (byte[]) row.get("totp_ciphertext"), (byte[]) row.get("totp_nonce"),
                ((Number) row.get("totp_key_version")).intValue());
        byte[] seed = eligible && passwordValid ? secrets.decrypt(operatorId, credentialVersion, encrypted) : new byte[20];
        var accepted = totp.verify(seed, code, now, lastStep);
        Arrays.fill(seed, (byte) 0);
        if (!eligible || !passwordValid || accepted.isEmpty()) {
            int attempts = ((Number) row.get("failed_attempt_count")).intValue() + 1;
            Instant lockedUntil = attempts >= 5 ? now.plusSeconds(Math.min(900, 1L << Math.min(attempts, 9))) : null;
            jdbc.update("update operations.operator_login_credentials set failed_attempt_count=?, locked_until=?, updated_at=? where operator_account_id=?",
                    attempts, lockedUntil == null ? null : Timestamp.from(lockedUntil), Timestamp.from(now), operatorId);
            audit(operatorId, attempts >= 5 ? "OPERATOR_LOGIN_LOCKED" : "OPERATOR_LOGIN_REJECTED",
                    attempts >= 5 ? "LOCKED" : "AUTHENTICATION_REJECTED", attempts >= 5 ? 429 : 401);
            throw attempts >= 5
                    ? new OperatorAuthenticationRejectedException("OPERATOR_AUTHENTICATION_RATE_LIMITED")
                    : rejected();
        }
        jdbc.update("update operations.operator_login_credentials set last_accepted_totp_step=?, failed_attempt_count=0, locked_until=null, updated_at=? where operator_account_id=?",
                accepted.getAsLong(), Timestamp.from(now), operatorId);
        String rawSession = tokens.randomToken();
        var sessionDigest = tokens.digest(OperatorTokenProtector.Domain.SESSION, rawSession);
        var csrf = tokens.deriveCsrf(rawSession, 1);
        UUID sessionId = UUID.randomUUID();
        Instant absolute = now.plus(absoluteLifetime);
        Instant idle = min(now.plus(idleLifetime), absolute);
        jdbc.update("""
                insert into operations.operator_sessions
                  (id, operator_account_id, credential_version, session_token_hmac, session_hmac_key_version,
                   csrf_token_hmac, csrf_hmac_key_version, csrf_generation, source_key_hmac,
                   source_hmac_key_version, created_at, last_used_at, idle_expires_at,
                   absolute_expires_at, mfa_verified_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, sessionId, operatorId, credentialVersion, sessionDigest.hexDigest(), sessionDigest.keyVersion(),
                csrf.digest().hexDigest(), csrf.digest().keyVersion(), 1, sourceDigest.hexDigest(),
                sourceDigest.keyVersion(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(idle),
                Timestamp.from(absolute), Timestamp.from(now));
        jdbc.update("""
                update operations.operator_sessions set revoked_at=?, revocation_reason_code='SESSION_LIMIT'
                where id in (select id from operations.operator_sessions
                  where operator_account_id=? and revoked_at is null order by created_at desc, id desc offset 3)
                """, Timestamp.from(now), operatorId);
        audit(operatorId, "OPERATOR_LOGIN_SUCCEEDED", "AUTHENTICATED", 201);
        throttle.clearLogin(loginDigest.hexDigest());
        return new IssuedSession(sessionId, operatorId, rawSession, csrf.rawToken(), now, idle, absolute);
    }

    public SessionPrincipal authenticate(String rawSessionToken) {
        return transactions.execute(status -> authenticateInTransaction(rawSessionToken, true));
    }

    private SessionPrincipal authenticateInTransaction(String rawSessionToken, boolean touch) {
        var digest = tokens.digest(OperatorTokenProtector.Domain.SESSION, rawSessionToken);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.operator_account_id, s.credential_version, s.session_hmac_key_version,
                       s.csrf_token_hmac, s.csrf_hmac_key_version, s.csrf_generation,
                       s.idle_expires_at, s.absolute_expires_at, s.mfa_verified_at, s.revoked_at,
                       a.status, c.credential_version current_credential_version, c.compromised_at
                from operations.operator_sessions s
                join operations.operator_accounts a on a.id=s.operator_account_id
                join operations.operator_login_credentials c on c.operator_account_id=a.id
                where s.session_token_hmac=? and s.session_hmac_key_version=? for update of s
                """, digest.hexDigest(), digest.keyVersion());
        if (rows.size() != 1) throw rejected();
        Map<String, Object> row = rows.getFirst();
        Instant now = clock.instant();
        long sessionVersion = ((Number) row.get("credential_version")).longValue();
        long currentVersion = ((Number) row.get("current_credential_version")).longValue();
        Instant idle = ((Timestamp) row.get("idle_expires_at")).toInstant();
        Instant absolute = ((Timestamp) row.get("absolute_expires_at")).toInstant();
        if (row.get("revoked_at") != null || !"ACTIVE".equals(row.get("status"))
                || row.get("compromised_at") != null || sessionVersion != currentVersion
                || !now.isBefore(idle) || !now.isBefore(absolute)) throw rejected();
        if (touch) {
            Instant nextIdle = min(now.plus(idleLifetime), absolute);
            jdbc.update("update operations.operator_sessions set last_used_at=?, idle_expires_at=? where id=?",
                    Timestamp.from(now), Timestamp.from(nextIdle), row.get("id"));
            idle = nextIdle;
        }
        return new SessionPrincipal((UUID) row.get("id"), (UUID) row.get("operator_account_id"),
                ((Timestamp) row.get("mfa_verified_at")).toInstant(),
                ((Number) row.get("csrf_generation")).longValue(), idle, absolute);
    }

    public boolean csrfMatches(String rawSessionToken, String rawCsrfToken) {
        SessionPrincipal principal = authenticate(rawSessionToken);
        var derived = tokens.deriveCsrf(rawSessionToken, principal.csrfGeneration());
        if (!java.security.MessageDigest.isEqual(
                derived.rawToken().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                (rawCsrfToken == null ? "" : rawCsrfToken).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return false;
        Integer count = jdbc.queryForObject("select count(*) from operations.operator_sessions where id=? and csrf_token_hmac=? and csrf_hmac_key_version=?",
                Integer.class, principal.sessionId(), derived.digest().hexDigest(), derived.digest().keyVersion());
        return count != null && count == 1;
    }

    public SessionView inspect(String rawSessionToken) {
        SessionPrincipal principal = authenticate(rawSessionToken);
        String csrf = tokens.deriveCsrf(rawSessionToken, principal.csrfGeneration()).rawToken();
        return new SessionView(principal.operatorId(), csrf, principal.mfaVerifiedAt(),
                principal.idleExpiresAt(), principal.absoluteExpiresAt());
    }

    public void logout(String rawSessionToken) {
        transactions.executeWithoutResult(status -> logoutInTransaction(rawSessionToken));
    }

    private void logoutInTransaction(String rawSessionToken) {
        SessionPrincipal principal = authenticateInTransaction(rawSessionToken, false);
        Instant now = clock.instant();
        jdbc.update("update operations.operator_sessions set revoked_at=?, revocation_reason_code='LOGOUT' where id=? and revoked_at is null",
                Timestamp.from(now), principal.sessionId());
        audit(principal.operatorId(), "OPERATOR_LOGOUT", "LOGGED_OUT", 204);
    }

    public ReauthenticatedSession reauthenticate(String rawSessionToken, char[] password, String code) {
        try {
            return transactions.execute(status -> reauthenticateInTransaction(rawSessionToken, password, code));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private ReauthenticatedSession reauthenticateInTransaction(
            String rawSessionToken, char[] password, String code) {
        SessionPrincipal principal = authenticateInTransaction(rawSessionToken, false);
        Map<String, Object> row = jdbc.queryForMap("""
                select c.password_hash, c.credential_version, c.totp_ciphertext, c.totp_nonce,
                       c.totp_key_version, c.last_accepted_totp_step
                from operations.operator_login_credentials c
                where c.operator_account_id=? for update
                """, principal.operatorId());
        long version = ((Number) row.get("credential_version")).longValue();
        boolean passwordValid = passwords.verify(password, row.get("password_hash").toString());
        byte[] seed = passwordValid ? secrets.decrypt(principal.operatorId(), version,
                new OperatorSecretCipher.EncryptedSecret((byte[]) row.get("totp_ciphertext"),
                        (byte[]) row.get("totp_nonce"), ((Number) row.get("totp_key_version")).intValue()))
                : new byte[20];
        long lastStep = row.get("last_accepted_totp_step") == null
                ? -1 : ((Number) row.get("last_accepted_totp_step")).longValue();
        Instant now = clock.instant();
        var accepted = totp.verify(seed, code, now, lastStep);
        Arrays.fill(seed, (byte) 0);
        if (!passwordValid || accepted.isEmpty()) {
            audit(principal.operatorId(), "OPERATOR_REAUTHENTICATION_REJECTED", "AUTHENTICATION_REJECTED", 401);
            throw rejected();
        }
        long generation = principal.csrfGeneration() + 1;
        var csrf = tokens.deriveCsrf(rawSessionToken, generation);
        jdbc.update("update operations.operator_login_credentials set last_accepted_totp_step=?, updated_at=? where operator_account_id=?",
                accepted.getAsLong(), Timestamp.from(now), principal.operatorId());
        jdbc.update("update operations.operator_sessions set mfa_verified_at=?, csrf_generation=?, csrf_token_hmac=?, csrf_hmac_key_version=? where id=?",
                Timestamp.from(now), generation, csrf.digest().hexDigest(), csrf.digest().keyVersion(), principal.sessionId());
        audit(principal.operatorId(), "OPERATOR_REAUTHENTICATED", "AUTHENTICATED", 200);
        return new ReauthenticatedSession(principal.operatorId(), csrf.rawToken(), now,
                principal.idleExpiresAt(), principal.absoluteExpiresAt());
    }

    private void audit(UUID actor, String action, String reason, int status) {
        audit(actor, action, reason, status, null, null);
    }

    private void audit(UUID actor, String action, String reason, int status, String loginKey, String sourceKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into operations.audit_events
                  (id, actor_type, actor_id, action_type, target_domain, target_id, reason_code,
                   correlation_id, idempotency_key, occurred_at, decision_status, response_status, response_code,
                   evidence_document)
                values (?, ?, ?, ?, 'OPERATOR_AUTH', ?, ?, ?, ?, ?, ?, ?, ?,
                        jsonb_build_object('loginKey', cast(? as text), 'sourceKey', cast(? as text)))
                """,
                id, actor.equals(PRE_AUTH_ACTOR) ? "PRE_AUTH" : "OPERATOR", actor, action, actor, reason,
                UUID.randomUUID(), "operator-auth:" + id, Timestamp.from(clock.instant()),
                status < 400 ? "SUCCEEDED" : "REJECTED", status, reason, loginKey, sourceKey);
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,119}")) throw rejected();
        return normalized;
    }

    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static OperatorAuthenticationRejectedException rejected() { return new OperatorAuthenticationRejectedException(); }

    public record IssuedSession(UUID sessionId, UUID operatorId, String rawSessionToken, String rawCsrfToken,
                                Instant mfaVerifiedAt,
                                Instant idleExpiresAt, Instant absoluteExpiresAt) {}
    public record SessionPrincipal(UUID sessionId, UUID operatorId, Instant mfaVerifiedAt,
                                   long csrfGeneration, Instant idleExpiresAt, Instant absoluteExpiresAt) {}
    public record SessionView(UUID operatorId, String rawCsrfToken, Instant mfaVerifiedAt,
                              Instant idleExpiresAt, Instant absoluteExpiresAt) {}
    public record ReauthenticatedSession(UUID operatorId, String rawCsrfToken, Instant mfaVerifiedAt,
                                         Instant idleExpiresAt, Instant absoluteExpiresAt) {}
}
