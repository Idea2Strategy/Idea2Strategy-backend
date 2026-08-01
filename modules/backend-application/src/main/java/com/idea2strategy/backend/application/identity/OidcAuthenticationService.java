package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class OidcAuthenticationService {
    private static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofHours(12);

    private final OidcIdentityQueryPort queryPort;
    private final IdentityCommandPort commandPort;
    private final OidcSubjectProtector subjectProtector;
    private final SessionTokenIssuer tokenIssuer;
    private final Clock clock;
    private final Duration sessionLifetime;
    private final int maxActiveSessions;

    public OidcAuthenticationService(
            OidcIdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            OidcSubjectProtector subjectProtector,
            SessionTokenIssuer tokenIssuer,
            Clock clock) {
        this(queryPort, commandPort, subjectProtector, tokenIssuer, clock, DEFAULT_SESSION_LIFETIME, 5);
    }

    public OidcAuthenticationService(
            OidcIdentityQueryPort queryPort,
            IdentityCommandPort commandPort,
            OidcSubjectProtector subjectProtector,
            SessionTokenIssuer tokenIssuer,
            Clock clock,
            Duration sessionLifetime,
            int maxActiveSessions) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.subjectProtector = Objects.requireNonNull(subjectProtector, "subjectProtector");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionLifetime = Objects.requireNonNull(sessionLifetime, "sessionLifetime");
        if (sessionLifetime.isZero() || sessionLifetime.isNegative()) {
            throw new IllegalArgumentException("sessionLifetime must be positive");
        }
        if (maxActiveSessions < 1) {
            throw new IllegalArgumentException("maxActiveSessions must be positive");
        }
        this.maxActiveSessions = maxActiveSessions;
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
                .orElseThrow(() -> new AuthenticationRejectedException("OIDC identity is not linked"));
        if (account.accountStatus() != AccountLifecycleStatus.ACTIVE
                || account.loginIdentityStatus() != LoginIdentityStatus.ACTIVE) {
            throw new AuthenticationRejectedException("Account is not active");
        }

        var now = clock.instant();
        var expiresAt = now.plus(sessionLifetime);
        UUID sessionId = UUID.randomUUID();
        SessionToken token = tokenIssuer.issue();
        var session = new AuthenticationSession(
                sessionId,
                account.accountId(),
                account.loginIdentityId(),
                account.authEpoch(),
                null,
                token.digest(),
                command.deviceLabel(),
                now,
                expiresAt);
        commandPort.completeLogin(
                session,
                new AuthenticationSuccess(
                        account.accountId(), account.loginIdentityId(), command.correlationId(), now),
                maxActiveSessions);
        return new LoginResult(account.accountId(), sessionId, token.rawToken(), expiresAt);
    }
}
