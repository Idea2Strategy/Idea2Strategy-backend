package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LifecycleOidcStepUpServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("13000000-0000-4000-8000-000000000001");
    private static final UUID LOGIN_ID = UUID.fromString("13000000-0000-4000-8000-000000000002");
    private static final UUID CHALLENGE_ID = UUID.fromString("13000000-0000-4000-8000-000000000003");

    @Test
    void verifiesBoundNonceAndDormantIdentityWithoutIssuingASession() {
        Fixture fixture = fixture("nonce-digest", "nonce-digest", AccountLifecycleStatus.DORMANT);

        LifecycleStepUp result = fixture.service.authenticate("test_oidc", "jwt", CHALLENGE_ID, UUID.randomUUID());

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.proof().providerCode()).isEqualTo("TEST_OIDC");
        assertThat(result.proof().challengeId()).isEqualTo(CHALLENGE_ID);
        verify(fixture.commands).recordStepUpSuccess(any());
        verify(fixture.commands, never()).createRefreshTokenFamily(any());
    }

    @Test
    void rejectsNonceMismatchOrAnAlreadyActiveAccount() {
        Fixture mismatch = fixture("stored", "different", AccountLifecycleStatus.DORMANT);
        assertThatThrownBy(() -> mismatch.service.authenticate(
                        "TEST_OIDC", "jwt", CHALLENGE_ID, UUID.randomUUID()))
                .isInstanceOf(AuthenticationRejectedException.class);
        verify(mismatch.commands, never()).recordStepUpSuccess(any());

        Fixture active = fixture("same", "same", AccountLifecycleStatus.ACTIVE);
        assertThatThrownBy(() -> active.service.authenticate(
                        "TEST_OIDC", "jwt", CHALLENGE_ID, UUID.randomUUID()))
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    @Test
    void rejectsAnExhaustedChallengeBeforeCallingTheTokenVerifier() {
        OidcIdentityQueryPort identities = mock(OidcIdentityQueryPort.class);
        OidcStepUpChallengePort challenges = mock(OidcStepUpChallengePort.class);
        OidcIdTokenVerifier verifier = mock(OidcIdTokenVerifier.class);
        when(challenges.find(CHALLENGE_ID)).thenReturn(Optional.of(new StoredOidcStepUpChallenge(
                CHALLENGE_ID, (short) 1, "TEST_OIDC", "same", NOW.plusSeconds(60), null, null)));
        when(identities.findProvider("TEST_OIDC")).thenReturn(Optional.of(
                new OidcProvider((short) 1, "TEST_OIDC", "https://issuer.test", true)));
        var service = new LifecycleOidcStepUpService(
                identities, mock(IdentityCommandPort.class), challenges, mock(OidcStepUpNonceSupport.class),
                verifier, mock(OidcSubjectProtector.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.authenticate(
                        "TEST_OIDC", "jwt", CHALLENGE_ID, UUID.randomUUID()))
                .isInstanceOf(AuthenticationRejectedException.class);
        verify(verifier, never()).verify(any());
    }

    @Test
    void reconstructsAConsumedChallengeWithoutCallingTheExpiredTokenVerifierSoTheReceiptCanReplay() {
        Fixture replay = fixture("same", "same", AccountLifecycleStatus.ACTIVE, NOW.minusSeconds(1), ACCOUNT_ID);

        LifecycleStepUp result = replay.service.authenticate(
                "TEST_OIDC", "jwt", CHALLENGE_ID, UUID.randomUUID());

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        verify(replay.verifier, never()).verify(any());
    }

    private static Fixture fixture(String storedDigest, String tokenDigest, AccountLifecycleStatus status) {
        return fixture(storedDigest, tokenDigest, status, null, null);
    }

    private static Fixture fixture(
            String storedDigest,
            String tokenDigest,
            AccountLifecycleStatus status,
            Instant consumedAt,
            UUID consumedByAccountId) {
        OidcIdentityQueryPort identities = mock(OidcIdentityQueryPort.class);
        IdentityCommandPort commands = mock(IdentityCommandPort.class);
        OidcStepUpChallengePort challenges = mock(OidcStepUpChallengePort.class);
        OidcStepUpNonceSupport nonces = mock(OidcStepUpNonceSupport.class);
        OidcIdTokenVerifier verifier = mock(OidcIdTokenVerifier.class);
        OidcSubjectProtector subjects = mock(OidcSubjectProtector.class);
        var provider = new OidcProvider((short) 1, "TEST_OIDC", "https://issuer.test", true);
        when(identities.findProvider("TEST_OIDC")).thenReturn(Optional.of(provider));
        when(challenges.find(CHALLENGE_ID)).thenReturn(Optional.of(new StoredOidcStepUpChallenge(
                CHALLENGE_ID, (short) 1, "TEST_OIDC", storedDigest,
                consumedAt == null ? NOW.plusSeconds(60) : NOW.minusSeconds(1),
                consumedAt, consumedByAccountId)));
        when(challenges.registerVerificationAttempt(CHALLENGE_ID, NOW)).thenReturn(true);
        when(verifier.verify(any())).thenReturn(new VerifiedOidcIdToken(
                "TEST_OIDC", "https://issuer.test", "subject", Set.of("client"), "raw-nonce", null,
                NOW.minusSeconds(60), NOW.plusSeconds(60), NOW, "kid"));
        when(nonces.digest("raw-nonce")).thenReturn(tokenDigest);
        when(subjects.protect(any())).thenReturn(new ProtectedOidcSubject("subject-digest", (short) 1));
        when(identities.findActiveLogin((short) 1, "subject-digest")).thenReturn(Optional.of(new OidcLoginAccount(
                ACCOUNT_ID, LOGIN_ID, status, LoginIdentityStatus.ACTIVE, 1)));
        return new Fixture(new LifecycleOidcStepUpService(
                identities, commands, challenges, nonces, verifier, subjects,
                Clock.fixed(NOW, ZoneOffset.UTC)), commands, verifier);
    }

    private record Fixture(
            LifecycleOidcStepUpService service,
            IdentityCommandPort commands,
            OidcIdTokenVerifier verifier) {}
}
