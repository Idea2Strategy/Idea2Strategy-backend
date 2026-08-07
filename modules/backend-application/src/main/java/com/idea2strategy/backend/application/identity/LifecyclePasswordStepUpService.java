package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Performs a credential check for account lifecycle commands without issuing access or refresh JWTs. */
public final class LifecyclePasswordStepUpService {
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final IdentityQueryPort identities;
    private final IdentityCommandPort commands;
    private final PasswordVerifier passwords;
    private final EmailLookup emails;
    private final Clock clock;

    public LifecyclePasswordStepUpService(
            IdentityQueryPort identities,
            IdentityCommandPort commands,
            PasswordVerifier passwords,
            EmailLookup emails,
            Clock clock) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.emails = Objects.requireNonNull(emails, "emails");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LifecycleStepUp authenticate(String email, String password, UUID correlationId) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(correlationId, "correlationId");
        PasswordLoginAccount account = identities.findPasswordLoginByEmailLookup(emails.lookup(email))
                .orElseThrow(() -> new AuthenticationRejectedException(INVALID_CREDENTIALS));
        var now = clock.instant();
        if (!passwords.matches(password, account.passwordHash())) {
            commands.recordLoginFailure(new LoginFailure(
                    account.accountId(),
                    account.loginIdentityId(),
                    "STEP_UP_INVALID_PASSWORD",
                    correlationId,
                    now));
            throw new AuthenticationRejectedException(INVALID_CREDENTIALS);
        }
        if (account.emailStatus() != EmailStatus.VERIFIED
                || account.loginIdentityStatus() != LoginIdentityStatus.ACTIVE
                || !supportsLifecycleStepUp(account.accountStatus())) {
            commands.recordLoginFailure(new LoginFailure(
                    account.accountId(),
                    account.loginIdentityId(),
                    "STEP_UP_NOT_ELIGIBLE",
                    correlationId,
                    now));
            throw new AuthenticationRejectedException(INVALID_CREDENTIALS);
        }
        commands.recordStepUpSuccess(new AuthenticationSuccess(
                account.accountId(), account.loginIdentityId(), correlationId, now));
        var proof = new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.PASSWORD,
                account.accountId(),
                null,
                null,
                now,
                now,
                true);
        return new LifecycleStepUp(account.accountId(), proof);
    }

    private static boolean supportsLifecycleStepUp(AccountLifecycleStatus status) {
        return status == AccountLifecycleStatus.ACTIVE
                || status == AccountLifecycleStatus.DORMANT
                || status == AccountLifecycleStatus.CLOSING;
    }
}
