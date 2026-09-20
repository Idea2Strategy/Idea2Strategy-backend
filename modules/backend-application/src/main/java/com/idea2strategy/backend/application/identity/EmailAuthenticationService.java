package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class EmailAuthenticationService {
    private static final Duration DEFAULT_REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    private final IdentityQueryPort queryPort;
    private final IdentityCommandPort commandPort;
    private final PasswordVerifier passwordVerifier;
    private final EmailLookup emailLookup;
    private final RefreshTokenSecretIssuer tokenIssuer;
    private final Clock clock;
    private final Duration refreshTokenLifetime;

    public EmailAuthenticationService(
            IdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            PasswordVerifier passwordVerifier,
            EmailLookup emailLookup,
            RefreshTokenSecretIssuer tokenIssuer,
            Clock clock) {
        this(queryPort, commandPort, passwordVerifier, emailLookup, tokenIssuer, clock, DEFAULT_REFRESH_TOKEN_LIFETIME);
    }

    public EmailAuthenticationService(
            IdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            PasswordVerifier passwordVerifier,
            EmailLookup emailLookup,
            RefreshTokenSecretIssuer tokenIssuer,
            Clock clock,
            Duration refreshTokenLifetime) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.passwordVerifier = Objects.requireNonNull(passwordVerifier, "passwordVerifier");
        this.emailLookup = Objects.requireNonNull(emailLookup, "emailLookup");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshTokenLifetime = Objects.requireNonNull(refreshTokenLifetime, "refreshTokenLifetime");
        if (refreshTokenLifetime.isZero() || refreshTokenLifetime.isNegative()) {
            throw new IllegalArgumentException("refreshTokenLifetime must be positive");
        }
    }

    public LoginResult login(LoginCommand command) {
        Objects.requireNonNull(command, "command");
        var account = queryPort.findPasswordLoginByEmailLookup(emailLookup.lookup(command.email()))
                .orElseThrow(() -> new AuthenticationRejectedException("Invalid email or password"));
        var now = clock.instant();

        if (!passwordVerifier.matches(command.password(), account.passwordHash())) {
            reject(account, command, "INVALID_PASSWORD", "Invalid email or password");
        }
        if (account.accountStatus() == AccountLifecycleStatus.PENDING_VERIFICATION
                || account.emailStatus() != EmailStatus.VERIFIED
                || account.loginIdentityStatus() == LoginIdentityStatus.PENDING) {
            reject(account, command, "EMAIL_NOT_VERIFIED", "Email verification is required");
        }
        if (account.accountStatus() != AccountLifecycleStatus.ACTIVE
                || account.loginIdentityStatus() != LoginIdentityStatus.ACTIVE) {
            reject(account, command, "ACCOUNT_NOT_ACTIVE", "Account is not active");
        }

        RefreshTokenSecret token = tokenIssuer.issue();
        UUID familyId = UUID.randomUUID();
        var expiresAt = now.plus(refreshTokenLifetime);
        var family = new RefreshTokenFamily(
                familyId,
                account.accountId(),
                account.loginIdentityId(),
                account.authEpoch(),
                account.credentialVersion(),
                token.digest(),
                now,
                expiresAt);
        commandPort.completeLogin(
                family,
                new AuthenticationSuccess(
                        account.accountId(), account.loginIdentityId(), command.correlationId(), now));
        return new LoginResult(
                account.accountId(), account.loginIdentityId(), account.authEpoch(), account.credentialVersion(),
                familyId, token.rawToken(), expiresAt);
    }

    /**
     * Mints the session a device authorization collects.
     *
     * <p>Deliberately the same path as a password login: the same refresh family, the same auth
     * epoch and credential version, the same success record. A device-approved session that did not
     * carry those would survive a password change, which is exactly what someone revoking access
     * expects it not to do. The password check is absent because the browser already performed it —
     * everything after it still applies.
     */
    public LoginResult completeApprovedDeviceLogin(UUID accountId, UUID correlationId) {
        Objects.requireNonNull(accountId, "accountId");
        var account = queryPort.findPasswordLoginByAccountId(accountId)
                .orElseThrow(() -> new AuthenticationRejectedException("Account is not available"));
        if (account.accountStatus() != AccountLifecycleStatus.ACTIVE
                || account.loginIdentityStatus() != LoginIdentityStatus.ACTIVE) {
            throw new AuthenticationRejectedException("Account is not active");
        }
        Instant now = clock.instant();
        RefreshTokenSecret token = tokenIssuer.issue();
        UUID familyId = UUID.randomUUID();
        var expiresAt = now.plus(refreshTokenLifetime);
        commandPort.completeLogin(
                new RefreshTokenFamily(
                        familyId,
                        account.accountId(),
                        account.loginIdentityId(),
                        account.authEpoch(),
                        account.credentialVersion(),
                        token.digest(),
                        now,
                        expiresAt),
                new AuthenticationSuccess(
                        account.accountId(), account.loginIdentityId(), correlationId, now));
        return new LoginResult(
                account.accountId(), account.loginIdentityId(), account.authEpoch(),
                account.credentialVersion(), familyId, token.rawToken(), expiresAt);
    }

    private void reject(PasswordLoginAccount account, LoginCommand command, String reason, String message) {
        commandPort.recordLoginFailure(new LoginFailure(
                account.accountId(), account.loginIdentityId(), reason, command.correlationId(), clock.instant()));
        throw new AuthenticationRejectedException(message);
    }
}
