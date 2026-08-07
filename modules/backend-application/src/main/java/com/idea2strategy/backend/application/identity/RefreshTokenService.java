package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class RefreshTokenService {
    private final RefreshTokenFamilyQueryPort queries;
    private final RefreshTokenFamilyCommandPort commands;
    private final Clock clock;
    private final RefreshTokenSecretIssuer tokenIssuer;
    private final Duration refreshTokenLifetime;

    public RefreshTokenService(RefreshTokenFamilyQueryPort queries, RefreshTokenFamilyCommandPort commands, Clock clock) {
        this(queries, commands, clock, null, Duration.ZERO);
    }

    public RefreshTokenService(
            RefreshTokenFamilyQueryPort queries,
            RefreshTokenFamilyCommandPort commands,
            Clock clock,
            RefreshTokenSecretIssuer tokenIssuer,
            Duration refreshTokenLifetime) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenIssuer = tokenIssuer;
        this.refreshTokenLifetime = Objects.requireNonNull(refreshTokenLifetime, "refreshTokenLifetime");
    }

    public void revokeCurrent(UUID familyId, String tokenDigest, UUID correlationId) {
        var current = loadCurrentToken(familyId, tokenDigest, correlationId, CustomerAccessScope.TOKEN_TEARDOWN);
        revoke(current, current.id(), "LOGOUT", correlationId);
    }

    public RotatedRefreshToken rotate(UUID familyId, String tokenDigest, UUID correlationId) {
        requireTokenDigest(tokenDigest);
        if (tokenIssuer == null || refreshTokenLifetime.isZero() || refreshTokenLifetime.isNegative()) {
            throw new IllegalStateException("Refresh token rotation is not configured");
        }
        var current = loadValid(familyId, correlationId, CustomerAccessScope.STANDARD);
        var replacement = tokenIssuer.issue();
        var now = clock.instant();
        var expiresAt = now.plus(refreshTokenLifetime);
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
                    "REFRESH_TOKEN_REJECTED", "ROTATION_RACE_LOST", correlationId, now);
            throw new AuthenticationRejectedException("Refresh token was already used");
        }
        return new RotatedRefreshToken(
                current.accountId(),
                current.loginIdentityId(),
                current.authEpochAtIssue(),
                current.credentialVersionAtIssue(),
                current.id(),
                replacement.rawToken(),
                expiresAt);
    }

    public int revokeAll(UUID familyId, String tokenDigest, UUID correlationId) {
        var current = loadCurrentToken(familyId, tokenDigest, correlationId, CustomerAccessScope.TOKEN_TEARDOWN);
        return commands.revokeAll(
                current.accountId(), "LOGOUT_ALL", Objects.requireNonNull(correlationId, "correlationId"), clock.instant());
    }

    private void revoke(StoredRefreshTokenFamily current, UUID targetFamilyId, String reason, UUID correlationId) {
        boolean revoked = commands.revoke(
                current.accountId(),
                targetFamilyId,
                reason,
                Objects.requireNonNull(correlationId, "correlationId"),
                clock.instant());
        if (!revoked) {
            commands.recordEvent(
                    current.accountId(),
                    current.loginIdentityId(),
                    current.id(),
                    "REFRESH_TOKEN_REJECTED",
                    "TARGET_NOT_OWNED_OR_ACTIVE",
                    correlationId,
                    clock.instant());
            throw new AuthenticationRejectedException("Refresh token family is not active for this account");
        }
    }

    private StoredRefreshTokenFamily loadValid(
            UUID familyId, UUID correlationId, CustomerAccessScope accessScope) {
        Objects.requireNonNull(familyId, "familyId");
        var family = queries.findById(familyId)
                .orElseThrow(() -> new AuthenticationRejectedException("Refresh token family is not valid"));
        return validate(family, correlationId, accessScope);
    }

    private StoredRefreshTokenFamily loadCurrentToken(
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

    private StoredRefreshTokenFamily validate(
            StoredRefreshTokenFamily family, UUID correlationId, CustomerAccessScope accessScope) {
        var now = clock.instant();
        String rejectionReason = rejectionReason(family, now);
        if (rejectionReason != null) {
            commands.recordEvent(
                    family.accountId(),
                    family.loginIdentityId(),
                    family.id(),
                    "REFRESH_TOKEN_REJECTED",
                    rejectionReason,
                    Objects.requireNonNull(correlationId, "correlationId"),
                    now);
            throw new AuthenticationRejectedException("Refresh token family is not valid");
        }
        Objects.requireNonNull(accessScope, "accessScope");
        if (family.activeSanction() && !accessScope.allowedDuringSanction()) {
            commands.recordEvent(
                    family.accountId(),
                    family.loginIdentityId(),
                    family.id(),
                    "REFRESH_TOKEN_REJECTED",
                    "ACTIVE_ACCOUNT_SANCTION",
                    Objects.requireNonNull(correlationId, "correlationId"),
                    now);
            throw new SanctionedAccountAccessException();
        }
        commands.touch(family.accountId(), family.id(), now);
        commands.recordEvent(
                family.accountId(),
                family.loginIdentityId(),
                family.id(),
                family.activeSanction() ? "SANCTION_RESTRICTED_ACCESS_VALIDATED" : "REFRESH_TOKEN_VALIDATED",
                family.activeSanction() ? accessScope.name() : null,
                Objects.requireNonNull(correlationId, "correlationId"),
                now);
        return family;
    }

    private static String rejectionReason(StoredRefreshTokenFamily family, java.time.Instant now) {
        if (family.revokedAt() != null) {
            return "REVOKED";
        }
        if (!family.expiresAt().isAfter(now)) {
            return "EXPIRED";
        }
        if (family.accountStatus() != AccountLifecycleStatus.ACTIVE) {
            return "ACCOUNT_NOT_ACTIVE";
        }
        if (family.loginIdentityStatus() != LoginIdentityStatus.ACTIVE) {
            return "LOGIN_IDENTITY_NOT_ACTIVE";
        }
        if (family.authEpochAtIssue() != family.currentAuthEpoch()) {
            return "AUTH_EPOCH_MISMATCH";
        }
        if (!Objects.equals(family.credentialVersionAtIssue(), family.currentCredentialVersion())) {
            return "CREDENTIAL_VERSION_MISMATCH";
        }
        return null;
    }
}
