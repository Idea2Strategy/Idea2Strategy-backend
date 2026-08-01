package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.UUID;

public final class PasswordRecoveryService {
    private final AccountRecoveryQueryPort queryPort;
    private final AccountRecoveryCommandPort commandPort;
    private final EmailLookup emailLookup;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final PasswordResetTokenIssuer tokenIssuer;
    private final PasswordResetTokenDigest tokenDigest;
    private final Clock clock;
    private final Duration resetLifetime;
    private final int recoveryCodeCount;

    public PasswordRecoveryService(
            AccountRecoveryQueryPort queryPort,
            AccountRecoveryCommandPort commandPort,
            EmailLookup emailLookup,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            PasswordResetTokenIssuer tokenIssuer,
            PasswordResetTokenDigest tokenDigest,
            Clock clock,
            Duration resetLifetime) {
        this(queryPort, commandPort, emailLookup, passwordPolicy, passwordHasher, tokenIssuer,
                tokenDigest, clock, resetLifetime, 10);
    }

    public PasswordRecoveryService(
            AccountRecoveryQueryPort queryPort,
            AccountRecoveryCommandPort commandPort,
            EmailLookup emailLookup,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            PasswordResetTokenIssuer tokenIssuer,
            PasswordResetTokenDigest tokenDigest,
            Clock clock,
            Duration resetLifetime,
            int recoveryCodeCount) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.emailLookup = Objects.requireNonNull(emailLookup, "emailLookup");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.tokenDigest = Objects.requireNonNull(tokenDigest, "tokenDigest");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resetLifetime = Objects.requireNonNull(resetLifetime, "resetLifetime");
        if (resetLifetime.isZero() || resetLifetime.isNegative()) {
            throw new IllegalArgumentException("resetLifetime must be positive");
        }
        if (recoveryCodeCount < 1) {
            throw new IllegalArgumentException("recoveryCodeCount must be positive");
        }
        this.recoveryCodeCount = recoveryCodeCount;
    }

    public Optional<PasswordResetDelivery> requestPasswordReset(RequestPasswordResetCommand command) {
        Objects.requireNonNull(command, "command");
        return queryPort.findPasswordRecoveryByEmailLookup(emailLookup.lookup(command.email()))
                .map(account -> issue(account, command));
    }

    public void resetPassword(ResetPasswordCommand command) {
        Objects.requireNonNull(command, "command");
        passwordPolicy.validate(command.newPassword());
        PasswordResetOutcome outcome = commandPort.consumePasswordReset(new PasswordResetConsumption(
                tokenDigest.digest(command.resetToken()),
                passwordHasher.hash(command.newPassword()),
                command.correlationId(),
                clock.instant()));
        if (outcome != PasswordResetOutcome.CHANGED) {
            throw new PasswordResetRejectedException();
        }
    }

    public IssuedRecoveryCodes issueRecoveryCodes(UUID accountId, UUID correlationId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(correlationId, "correlationId");
        queryPort.findPasswordRecoveryByAccountId(accountId)
                .orElseThrow(() -> new AuthenticationRejectedException("Password recovery is not available"));
        var rawCodes = new ArrayList<String>(recoveryCodeCount);
        var storedCodes = new ArrayList<StoredRecoveryCode>(recoveryCodeCount);
        for (int index = 0; index < recoveryCodeCount; index++) {
            PasswordResetToken token = tokenIssuer.issue();
            rawCodes.add(token.rawToken());
            storedCodes.add(new StoredRecoveryCode(UUID.randomUUID(), token.digest()));
        }
        commandPort.replaceRecoveryCodes(new RecoveryCodeBatch(
                UUID.randomUUID(), accountId, storedCodes, clock.instant(), correlationId));
        return new IssuedRecoveryCodes(rawCodes);
    }

    public void recoverWithCode(RecoverWithCodeCommand command) {
        Objects.requireNonNull(command, "command");
        passwordPolicy.validate(command.newPassword());
        PasswordRecoveryAccount account = queryPort
                .findPasswordRecoveryByEmailLookup(emailLookup.lookup(command.email()))
                .orElseThrow(PasswordResetRejectedException::new);
        RecoveryCodeOutcome outcome = commandPort.consumeRecoveryCode(new RecoveryCodeConsumption(
                account.accountId(),
                tokenDigest.digest(command.recoveryCode()),
                passwordHasher.hash(command.newPassword()),
                command.correlationId(),
                clock.instant()));
        if (outcome != RecoveryCodeOutcome.RECOVERED) {
            throw new PasswordResetRejectedException();
        }
    }

    private PasswordResetDelivery issue(
            PasswordRecoveryAccount account, RequestPasswordResetCommand command) {
        var now = clock.instant();
        var expiresAt = now.plus(resetLifetime);
        PasswordResetToken token = tokenIssuer.issue();
        commandPort.issuePasswordReset(new PendingPasswordReset(
                UUID.randomUUID(),
                account.accountId(),
                account.loginIdentityId(),
                account.authEpoch(),
                account.credentialVersion(),
                token.digest(),
                now,
                expiresAt,
                command.correlationId(),
                command.requestIpPrefix()));
        return new PasswordResetDelivery(account.accountId(), token.rawToken(), expiresAt);
    }
}
