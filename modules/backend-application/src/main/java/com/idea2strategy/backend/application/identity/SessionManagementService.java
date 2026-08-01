package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
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

    public AuthenticatedSession authenticate(String tokenDigest) {
        return authenticate(tokenDigest, UUID.randomUUID());
    }

    public AuthenticatedSession authenticate(String tokenDigest, UUID correlationId) {
        var session = loadValid(tokenDigest, correlationId);
        return new AuthenticatedSession(session.accountId(), session.id());
    }

    public List<SessionView> list(String tokenDigest) {
        return list(tokenDigest, UUID.randomUUID());
    }

    public List<SessionView> list(String tokenDigest, UUID correlationId) {
        var current = loadValid(tokenDigest, correlationId);
        return queries.findActiveByAccountId(current.accountId(), clock.instant()).stream()
                .map(session -> new SessionView(
                        session.sessionId(),
                        session.deviceLabel(),
                        session.issuedAt(),
                        session.lastSeenAt(),
                        session.expiresAt(),
                        session.sessionId().equals(current.id())))
                .toList();
    }

    public void revokeCurrent(String tokenDigest, UUID correlationId) {
        var current = loadValid(tokenDigest, correlationId);
        revoke(current, current.id(), "LOGOUT", correlationId);
    }

    public RotatedSession rotate(String tokenDigest, UUID correlationId) {
        if (tokenIssuer == null || sessionLifetime.isZero() || sessionLifetime.isNegative()) {
            throw new IllegalStateException("Session rotation is not configured");
        }
        var current = loadValid(tokenDigest, correlationId);
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
            commands.recordEvent(
                    current.accountId(), current.loginIdentityId(), current.id(),
                    "SESSION_REJECTED", "ROTATION_RACE_LOST", correlationId, now);
            throw new AuthenticationRejectedException("Session is no longer active");
        }
        return new RotatedSession(current.id(), replacement.rawToken(), expiresAt);
    }

    public void revokeOther(String tokenDigest, UUID targetSessionId, UUID correlationId) {
        Objects.requireNonNull(targetSessionId, "targetSessionId");
        var current = loadValid(tokenDigest, correlationId);
        if (current.id().equals(targetSessionId)) {
            commands.recordEvent(
                    current.accountId(), current.loginIdentityId(), current.id(),
                    "SESSION_REJECTED", "CURRENT_SESSION_REMOTE_TARGET", correlationId, clock.instant());
            throw new IllegalArgumentException("Use current-session logout for the current session");
        }
        revoke(current, targetSessionId, "REMOTE_LOGOUT", correlationId);
    }

    public int revokeAll(String tokenDigest, UUID correlationId) {
        var current = loadValid(tokenDigest, correlationId);
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

    private StoredSession loadValid(String tokenDigest, UUID correlationId) {
        if (tokenDigest == null || tokenDigest.isBlank()) {
            throw new AuthenticationRejectedException("A session token is required");
        }
        var now = clock.instant();
        var session = queries.findByTokenDigest(tokenDigest)
                .orElseThrow(() -> new AuthenticationRejectedException("Session is not valid"));
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
        commands.touch(session.accountId(), session.id(), now);
        commands.recordEvent(
                session.accountId(),
                session.loginIdentityId(),
                session.id(),
                "SESSION_VALIDATED",
                null,
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
