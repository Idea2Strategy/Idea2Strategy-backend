package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class SessionManagementService {
    private final SessionQueryPort queries;
    private final SessionCommandPort commands;
    private final Clock clock;
    private final SessionTokenIssuer tokenIssuer;
    private final Duration sessionLifetime;

    public SessionManagementService(SessionQueryPort queries, SessionCommandPort commands, Clock clock) {
        this(queries, commands, clock, null, Duration.ZERO);
    }

    public SessionManagementService(
            SessionQueryPort queries,
            SessionCommandPort commands,
            Clock clock,
            SessionTokenIssuer tokenIssuer,
            Duration sessionLifetime) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenIssuer = tokenIssuer;
        this.sessionLifetime = Objects.requireNonNull(sessionLifetime, "sessionLifetime");
    }

    public void revokeCurrent(UUID familyId, String tokenDigest, UUID correlationId) {
        var current = loadCurrentToken(familyId, tokenDigest, correlationId, CustomerAccessScope.SESSION_TEARDOWN);
        revoke(current, current.id(), "LOGOUT", correlationId);
    }

    public RotatedSession rotate(UUID familyId, String tokenDigest, UUID correlationId) {
        requireTokenDigest(tokenDigest);
        if (tokenIssuer == null || sessionLifetime.isZero() || sessionLifetime.isNegative()) {
            throw new IllegalStateException("Session rotation is not configured");
        }
        var current = loadValid(familyId, correlationId, CustomerAccessScope.STANDARD);
        var replacement = tokenIssuer.issue();
        var now = clock.instant();
        var expiresAt = now.plus(sessionLifetime);
        boolean rotated = commands.rotate(
                current.accountId(),
                current.id(),
                tokenDigest,
                replacement.digest(),
                expiresAt,
                Objects.requireNonNull(correlationId, "correlationId"),
                now);
        if (!rotated) {
            commands.revoke(current.accountId(), current.id(), "REFRESH_TOKEN_REUSE", correlationId, now);
            commands.recordEvent(
                    current.accountId(), current.loginIdentityId(), current.id(),
                    "SESSION_REJECTED", "ROTATION_RACE_LOST", correlationId, now);
            throw new AuthenticationRejectedException("Refresh token was already used");
        }
        return new RotatedSession(
                current.accountId(),
                current.loginIdentityId(),
                current.authEpochAtIssue(),
                current.credentialVersionAtIssue(),
                current.id(),
                replacement.rawToken(),
                expiresAt);
    }

    public int revokeAll(UUID familyId, String tokenDigest, UUID correlationId) {
        var current = loadCurrentToken(familyId, tokenDigest, correlationId, CustomerAccessScope.SESSION_TEARDOWN);
        return commands.revokeAll(
                current.accountId(), "LOGOUT_ALL", Objects.requireNonNull(correlationId, "correlationId"), clock.instant());
    }

    private void revoke(StoredSession current, UUID targetSessionId, String reason, UUID correlationId) {
        boolean revoked = commands.revoke(
                current.accountId(),
                targetSessionId,
                reason,
                Objects.requireNonNull(correlationId, "correlationId"),
                clock.instant());
        if (!revoked) {
            commands.recordEvent(
                    current.accountId(),
                    current.loginIdentityId(),
                    current.id(),
                    "SESSION_REJECTED",
                    "TARGET_NOT_OWNED_OR_ACTIVE",
                    correlationId,
                    clock.instant());
            throw new AuthenticationRejectedException("Session is not active for this account");
        }
    }

    private StoredSession loadValid(
            UUID familyId, UUID correlationId, CustomerAccessScope accessScope) {
        Objects.requireNonNull(familyId, "familyId");
        var session = queries.findById(familyId)
                .orElseThrow(() -> new AuthenticationRejectedException("Refresh token family is not valid"));
        return validate(session, correlationId, accessScope);
    }

    private StoredSession loadCurrentToken(
            UUID familyId, String tokenDigest, UUID correlationId, CustomerAccessScope accessScope) {
        Objects.requireNonNull(familyId, "familyId");
        requireTokenDigest(tokenDigest);
        var family = queries.findByTokenDigest(tokenDigest)
                .filter(candidate -> familyId.equals(candidate.id()))
                .orElseThrow(() -> new AuthenticationRejectedException("Refresh token is not current"));
        return validate(family, correlationId, accessScope);
    }

    private void requireTokenDigest(String tokenDigest) {
        if (tokenDigest == null || tokenDigest.isBlank()) {
            throw new AuthenticationRejectedException("A refresh token is required");
        }
    }

    private StoredSession validate(
            StoredSession session, UUID correlationId, CustomerAccessScope accessScope) {
        var now = clock.instant();
        String rejectionReason = rejectionReason(session, now);
        if (rejectionReason != null) {
            commands.recordEvent(
                    session.accountId(),
                    session.loginIdentityId(),
                    session.id(),
                    "SESSION_REJECTED",
                    rejectionReason,
                    Objects.requireNonNull(correlationId, "correlationId"),
                    now);
            throw new AuthenticationRejectedException("Session is not valid");
        }
        Objects.requireNonNull(accessScope, "accessScope");
        if (session.activeSanction() && !accessScope.allowedDuringSanction()) {
            commands.recordEvent(
                    session.accountId(),
                    session.loginIdentityId(),
                    session.id(),
                    "SESSION_REJECTED",
                    "ACTIVE_ACCOUNT_SANCTION",
                    Objects.requireNonNull(correlationId, "correlationId"),
                    now);
            throw new SanctionedAccountAccessException();
        }
        commands.touch(session.accountId(), session.id(), now);
        commands.recordEvent(
                session.accountId(),
                session.loginIdentityId(),
                session.id(),
                session.activeSanction() ? "SANCTION_RESTRICTED_ACCESS_VALIDATED" : "SESSION_VALIDATED",
                session.activeSanction() ? accessScope.name() : null,
                Objects.requireNonNull(correlationId, "correlationId"),
                now);
        return session;
    }

    private static String rejectionReason(StoredSession session, java.time.Instant now) {
        if (session.revokedAt() != null) {
            return "REVOKED";
        }
        if (!session.expiresAt().isAfter(now)) {
            return "EXPIRED";
        }
        if (session.accountStatus() != AccountLifecycleStatus.ACTIVE) {
            return "ACCOUNT_NOT_ACTIVE";
        }
        if (session.loginIdentityStatus() != LoginIdentityStatus.ACTIVE) {
            return "LOGIN_IDENTITY_NOT_ACTIVE";
        }
        if (session.authEpochAtIssue() != session.currentAuthEpoch()) {
            return "AUTH_EPOCH_MISMATCH";
        }
        if (!Objects.equals(session.credentialVersionAtIssue(), session.currentCredentialVersion())) {
            return "CREDENTIAL_VERSION_MISMATCH";
        }
        return null;
    }
}
