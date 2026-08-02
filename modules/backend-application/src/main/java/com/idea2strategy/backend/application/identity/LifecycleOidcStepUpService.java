package com.idea2strategy.backend.application.identity;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Verifies an OIDC step-up without creating an application session. */
public final class LifecycleOidcStepUpService {
    private final OidcIdentityQueryPort identities;
    private final IdentityCommandPort commands;
    private final OidcStepUpChallengePort challenges;
    private final OidcStepUpNonceSupport nonces;
    private final OidcIdTokenVerifier verifier;
    private final OidcSubjectProtector subjects;
    private final Clock clock;

    public LifecycleOidcStepUpService(
            OidcIdentityQueryPort identities,
            IdentityCommandPort commands,
            OidcStepUpChallengePort challenges,
            OidcStepUpNonceSupport nonces,
            OidcIdTokenVerifier verifier,
            OidcSubjectProtector subjects,
            Clock clock) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LifecycleStepUp authenticate(
            String requestedProviderCode, String idToken, UUID challengeId, UUID correlationId) {
        Objects.requireNonNull(idToken, "idToken");
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(correlationId, "correlationId");
        String providerCode = Objects.requireNonNull(requestedProviderCode, "providerCode")
                .trim().toUpperCase(Locale.ROOT);
        var now = clock.instant();
        StoredOidcStepUpChallenge challenge = challenges.find(challengeId)
                .filter(candidate -> candidate.providerCode().equals(providerCode)
                        && (candidate.consumedAt() != null || candidate.unexpiredAt(now)))
                .orElseThrow(LifecycleOidcStepUpService::rejected);

        // A completed command receipt must remain replayable even after the one-time
        // credential has expired or its upstream JWKS endpoint is unavailable. The
        // persistence command boundary remains authoritative: only the original
        // idempotency key and request hash return the receipt; every new command is
        // evaluated against the current ACTIVE projection and rejected.
        if (challenge.consumedAt() != null) {
            return new LifecycleStepUp(challenge.consumedByAccountId(), new AccountLifecycleAuthenticationProof(
                    AccountLifecycleAuthenticationMethod.OIDC,
                    challenge.consumedByAccountId(),
                    providerCode,
                    challengeId,
                    challenge.consumedAt(),
                    challenge.consumedAt(),
                    true));
        }

        OidcProvider provider = identities.findProvider(providerCode)
                .filter(candidate -> candidate.active()
                        && candidate.id() == challenge.providerId()
                        && candidate.code().equals(providerCode))
                .orElseThrow(LifecycleOidcStepUpService::rejected);
        if (!challenges.registerVerificationAttempt(challengeId, now)) {
            throw rejected();
        }

        VerifiedOidcIdToken token = verifier.verify(new OidcIdTokenVerificationRequest(providerCode, idToken));
        if (!provider.issuer().equals(token.issuer())
                || !MessageDigest.isEqual(
                        challenge.nonceDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        nonces.digest(token.nonce()).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw rejected();
        }

        ProtectedOidcSubject subject = subjects.protect(token.principal());
        OidcLoginAccount account = identities.findActiveLogin(provider.id(), subject.hmac())
                .orElseThrow(LifecycleOidcStepUpService::rejected);
        if (account.accountStatus() != AccountLifecycleStatus.DORMANT
                || account.loginIdentityStatus() != LoginIdentityStatus.ACTIVE) {
            throw rejected();
        }
        commands.recordStepUpSuccess(new AuthenticationSuccess(
                account.accountId(), account.loginIdentityId(), correlationId, now));
        return new LifecycleStepUp(account.accountId(), new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.OIDC,
                account.accountId(),
                providerCode,
                challengeId,
                token.authenticatedAt(),
                token.verifiedAt(),
                true));
    }

    private static AuthenticationRejectedException rejected() {
        return new AuthenticationRejectedException("OIDC step-up was rejected");
    }
}
