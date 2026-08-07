package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import com.idea2strategy.backend.domain.identity.AccountPreferenceDefaults;

public final class OidcAuthenticationService {
    private static final Duration DEFAULT_REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    private final OidcIdentityQueryPort queryPort;
    private final IdentityCommandPort commandPort;
    private final OidcSubjectProtector subjectProtector;
    private final RefreshTokenSecretIssuer tokenIssuer;
    private final Clock clock;
    private final Duration refreshTokenLifetime;
    private final RegistrationQueryPort registrationQueries;
    private final OidcIdentityCommandPort oidcCommands;
    private final EmailProtector emailProtector;
    private final AccountPreferenceDefaults preferenceDefaults;

    public OidcAuthenticationService(
            OidcIdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            OidcSubjectProtector subjectProtector,
            RefreshTokenSecretIssuer tokenIssuer,
            Clock clock) {
        this(queryPort, commandPort, subjectProtector, tokenIssuer, clock, DEFAULT_REFRESH_TOKEN_LIFETIME);
    }

    public OidcAuthenticationService(
            OidcIdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            OidcSubjectProtector subjectProtector,
            RefreshTokenSecretIssuer tokenIssuer,
            Clock clock,
            Duration refreshTokenLifetime) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.subjectProtector = Objects.requireNonNull(subjectProtector, "subjectProtector");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshTokenLifetime = Objects.requireNonNull(refreshTokenLifetime, "refreshTokenLifetime");
        if (refreshTokenLifetime.isZero() || refreshTokenLifetime.isNegative()) {
            throw new IllegalArgumentException("refreshTokenLifetime must be positive");
        }
        this.registrationQueries = null;
        this.oidcCommands = null;
        this.emailProtector = null;
        this.preferenceDefaults = null;
    }

    public OidcAuthenticationService(
            OidcIdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            OidcSubjectProtector subjectProtector,
            RefreshTokenSecretIssuer tokenIssuer,
            Clock clock,
            Duration refreshTokenLifetime,
            RegistrationQueryPort registrationQueries,
            OidcIdentityCommandPort oidcCommands,
            EmailProtector emailProtector,
            AccountPreferenceDefaults preferenceDefaults) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.subjectProtector = Objects.requireNonNull(subjectProtector, "subjectProtector");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshTokenLifetime = Objects.requireNonNull(refreshTokenLifetime, "refreshTokenLifetime");
        if (refreshTokenLifetime.isZero() || refreshTokenLifetime.isNegative()) {
            throw new IllegalArgumentException("OIDC refresh-token configuration is invalid");
        }
        this.registrationQueries = Objects.requireNonNull(registrationQueries, "registrationQueries");
        this.oidcCommands = Objects.requireNonNull(oidcCommands, "oidcCommands");
        this.emailProtector = Objects.requireNonNull(emailProtector, "emailProtector");
        this.preferenceDefaults = Objects.requireNonNull(preferenceDefaults, "preferenceDefaults");
    }

    public LoginResult login(OidcLoginCommand command) {
        Objects.requireNonNull(command, "command");
        String providerCode = command.providerCode().trim().toUpperCase(Locale.ROOT);
        OidcProvider provider = queryPort.findProvider(providerCode)
                .filter(candidate -> candidate.active()
                        && candidate.code().equals(providerCode)
                        && candidate.issuer().equals(command.issuer()))
                .orElseThrow(() -> new AuthenticationRejectedException("OIDC provider is not trusted"));

        var principal = new VerifiedOidcPrincipal(
                providerCode, command.issuer(), command.subject(), command.email());
        ProtectedOidcSubject protectedSubject = subjectProtector.protect(principal);
        OidcLoginAccount account = queryPort.findActiveLogin(provider.id(), protectedSubject.hmac())
                .orElseGet(() -> registerNewAccount(command, provider, protectedSubject));
        if (account.accountStatus() != AccountLifecycleStatus.ACTIVE
                || account.loginIdentityStatus() != LoginIdentityStatus.ACTIVE) {
            throw new AuthenticationRejectedException("Account is not active");
        }

        var now = clock.instant();
        var expiresAt = now.plus(refreshTokenLifetime);
        UUID familyId = UUID.randomUUID();
        RefreshTokenSecret token = tokenIssuer.issue();
        var family = new RefreshTokenFamily(
                familyId,
                account.accountId(),
                account.loginIdentityId(),
                account.authEpoch(),
                null,
                token.digest(),
                now,
                expiresAt);
        commandPort.completeLogin(
                family,
                new AuthenticationSuccess(
                        account.accountId(), account.loginIdentityId(), command.correlationId(), now));
        return new LoginResult(
                account.accountId(), account.loginIdentityId(), account.authEpoch(), null,
                familyId, token.rawToken(), expiresAt);
    }

    private OidcLoginAccount registerNewAccount(
            OidcLoginCommand command, OidcProvider provider, ProtectedOidcSubject protectedSubject) {
        if (registrationQueries == null || command.email() == null || command.email().isBlank()) {
            throw new AuthenticationRejectedException("OIDC identity is not linked");
        }
        ProtectedEmail email = emailProtector.protect(command.email());
        if (registrationQueries.emailExists(email.lookupHmac())) {
            throw new AuthenticationRejectedException("OIDC email belongs to an existing account; explicit linking is required");
        }
        Instant now = clock.instant();
        UUID accountId = UUID.randomUUID();
        UUID loginIdentityId = UUID.randomUUID();
        oidcCommands.createActiveRegistration(new PendingOidcRegistration(
                accountId,
                loginIdentityId,
                provider.id(),
                protectedSubject,
                email,
                command.correlationId(),
                now,
                preferenceDefaults.at(now)));
        return new OidcLoginAccount(
                accountId, loginIdentityId, AccountLifecycleStatus.ACTIVE, LoginIdentityStatus.ACTIVE, 1);
    }
}
