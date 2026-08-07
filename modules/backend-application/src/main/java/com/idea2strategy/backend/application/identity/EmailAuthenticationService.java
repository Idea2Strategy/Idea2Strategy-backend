package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class EmailAuthenticationService {
    private static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofDays(30);

    private final IdentityQueryPort queryPort;
    private final IdentityCommandPort commandPort;
    private final PasswordVerifier passwordVerifier;
    private final EmailLookup emailLookup;
    private final SessionTokenIssuer tokenIssuer;
    private final Clock clock;
    private final Duration sessionLifetime;

    public EmailAuthenticationService(
            IdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            PasswordVerifier passwordVerifier,
            EmailLookup emailLookup,
            SessionTokenIssuer tokenIssuer,
            Clock clock) {
        this(queryPort, commandPort, passwordVerifier, emailLookup, tokenIssuer, clock, DEFAULT_SESSION_LIFETIME);
    }

    public EmailAuthenticationService(
            IdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            PasswordVerifier passwordVerifier,
            EmailLookup emailLookup,
            SessionTokenIssuer tokenIssuer,
            Clock clock,
            Duration sessionLifetime) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.passwordVerifier = Objects.requireNonNull(passwordVerifier, "passwordVerifier");
        this.emailLookup = Objects.requireNonNull(emailLookup, "emailLookup");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionLifetime = Objects.requireNonNull(sessionLifetime, "sessionLifetime");
        if (sessionLifetime.isZero() || sessionLifetime.isNegative()) {
            throw new IllegalArgumentException("sessionLifetime must be positive");
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

        SessionToken token = tokenIssuer.issue();
        UUID sessionId = UUID.randomUUID();
        var expiresAt = now.plus(sessionLifetime);
        var session = new AuthenticationSession(
                sessionId,
                account.accountId(),
                account.loginIdentityId(),
                account.authEpoch(),
                account.credentialVersion(),
                token.digest(),
                command.deviceLabel(),
                now,
                expiresAt);
        commandPort.completeLogin(
                session,
                new AuthenticationSuccess(
                        account.accountId(), account.loginIdentityId(), command.correlationId(), now));
        return new LoginResult(
                account.accountId(), account.loginIdentityId(), account.authEpoch(), account.credentialVersion(),
                sessionId, token.rawToken(), expiresAt);
    }

    private void reject(PasswordLoginAccount account, LoginCommand command, String reason, String message) {
        commandPort.recordLoginFailure(new LoginFailure(
                account.accountId(), account.loginIdentityId(), reason, command.correlationId(), clock.instant()));
        throw new AuthenticationRejectedException(message);
    }
}
