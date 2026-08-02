package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountPreferenceDefaults;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class EmailRegistrationService {
    private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);

    private final RegistrationQueryPort queryPort;
    private final RegistrationCommandPort commandPort;
    private final EmailProtector emailProtector;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final VerificationTokenIssuer tokenIssuer;
    private final VerificationTokenDigest tokenDigest;
    private final AccountPreferenceDefaults preferenceDefaults;
    private final Clock clock;

    public EmailRegistrationService(
            RegistrationQueryPort queryPort,
            RegistrationCommandPort commandPort,
            EmailProtector emailProtector,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            VerificationTokenIssuer tokenIssuer,
            VerificationTokenDigest tokenDigest,
            Clock clock) {
        this(
                queryPort,
                commandPort,
                emailProtector,
                passwordPolicy,
                passwordHasher,
                tokenIssuer,
                tokenDigest,
                new AccountPreferenceDefaults("ko", "America/New_York", ThemePreference.SYSTEM),
                clock);
    }

    public EmailRegistrationService(
            RegistrationQueryPort queryPort,
            RegistrationCommandPort commandPort,
            EmailProtector emailProtector,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            VerificationTokenIssuer tokenIssuer,
            VerificationTokenDigest tokenDigest,
            AccountPreferenceDefaults preferenceDefaults,
            Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.emailProtector = Objects.requireNonNull(emailProtector, "emailProtector");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.tokenDigest = Objects.requireNonNull(tokenDigest, "tokenDigest");
        this.preferenceDefaults = Objects.requireNonNull(preferenceDefaults, "preferenceDefaults");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SignupResult signup(SignupCommand command) {
        Objects.requireNonNull(command, "command");
        passwordPolicy.validate(command.password());
        ProtectedEmail email = emailProtector.protect(command.email());
        validateEmail(email.normalized());
        if (queryPort.emailExists(email.lookupHmac())) {
            throw new DuplicateEmailException();
        }

        var now = clock.instant();
        var expiresAt = now.plus(VERIFICATION_LIFETIME);
        VerificationToken token = tokenIssuer.issue();
        UUID accountId = UUID.randomUUID();
        commandPort.createPending(new PendingRegistration(
                accountId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                email,
                passwordHasher.hash(command.password()),
                token.digest(),
                now,
                expiresAt,
                command.correlationId(),
                command.requestIpPrefix(),
                preferenceDefaults.at(now)));
        return new SignupResult(accountId, token.rawToken(), expiresAt);
    }

    public void verify(VerifyEmailCommand command) {
        Objects.requireNonNull(command, "command");
        VerificationOutcome outcome = commandPort.consumeVerification(
                tokenDigest.digest(command.verificationToken()), clock.instant(), command.correlationId());
        switch (outcome) {
            case VERIFIED -> { return; }
            case EXPIRED -> throw new VerificationRejectedException("Verification token has expired");
            case ALREADY_USED, ACCOUNT_NOT_PENDING ->
                    throw new VerificationRejectedException("Verification token is no longer valid");
            case NOT_FOUND -> throw new VerificationRejectedException("Verification token is invalid");
        }
    }

    public VerificationDelivery resendVerification(ResendVerificationCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        var expiresAt = now.plus(VERIFICATION_LIFETIME);
        VerificationToken token = tokenIssuer.issue();
        commandPort.replaceVerification(new VerificationReplacement(
                UUID.randomUUID(),
                command.accountId(),
                token.digest(),
                now,
                expiresAt,
                command.correlationId(),
                command.requestIpPrefix()));
        return new VerificationDelivery(token.rawToken(), expiresAt);
    }

    private static void validateEmail(String normalizedEmail) {
        int at = normalizedEmail.lastIndexOf('@');
        if (normalizedEmail.length() > 254 || at < 1 || at == normalizedEmail.length() - 1
                || normalizedEmail.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Email address is invalid");
        }
    }
}
