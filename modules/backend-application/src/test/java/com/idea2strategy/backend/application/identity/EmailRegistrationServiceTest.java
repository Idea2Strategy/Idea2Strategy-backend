package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.identity.AccountPreferenceDefaults;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailRegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void signupStoresOnlyProtectedEmailPasswordHashAndTokenDigest() {
        var commands = new RecordingRegistrationPort();
        var service = service(false, commands);

        SignupResult result = service.signup(new SignupCommand(
                " Person@Example.com ", "a sufficiently long passphrase", UUID.randomUUID(), "192.0.2.0/24"));

        assertThat(result.verificationToken()).isEqualTo("raw-verification-token");
        assertThat(commands.registrations).singleElement().satisfies(registration -> {
            assertThat(registration.email().normalized()).isEqualTo("person@example.com");
            assertThat(registration.email().ciphertext()).isEqualTo("ciphertext:person@example.com");
            assertThat(registration.email().lookupHmac()).isEqualTo("lookup:person@example.com");
            assertThat(registration.password().encodedHash()).isEqualTo("hash:a sufficiently long passphrase");
            assertThat(registration.verificationTokenDigest()).isEqualTo("digest:raw-verification-token");
            assertThat(registration.preferences().languageCode()).isEqualTo("ko");
            assertThat(registration.preferences().timezoneName()).isEqualTo("America/New_York");
            assertThat(registration.preferences().themePreference()).isEqualTo(ThemePreference.SYSTEM);
            assertThat(registration.preferences().updatedAt()).isEqualTo(NOW);
            assertThat(registration.toString()).doesNotContain("a sufficiently long passphrase");
        });
    }

    @Test
    void signupCarriesConfiguredValidatedPreferenceDefaultsIntoTheAtomicRegistration() {
        var commands = new RecordingRegistrationPort();
        var service = service(false, commands, new AccountPreferenceDefaults("en", "America/Chicago", ThemePreference.SYSTEM));

        service.signup(new SignupCommand(
                "person@example.com", "a sufficiently long passphrase", UUID.randomUUID(), null));

        assertThat(commands.registrations).singleElement().satisfies(registration -> {
            assertThat(registration.preferences().languageCode()).isEqualTo("en");
            assertThat(registration.preferences().timezoneName()).isEqualTo("America/Chicago");
            assertThat(registration.preferences().themePreference()).isEqualTo(ThemePreference.SYSTEM);
        });
    }

    @Test
    void duplicateEmailAndShortPasswordAreRejected() {
        assertThatThrownBy(() -> service(true, new RecordingRegistrationPort()).signup(new SignupCommand(
                        "person@example.com", "a sufficiently long passphrase", UUID.randomUUID(), null)))
                .isInstanceOf(DuplicateEmailException.class);

        assertThatThrownBy(() -> service(false, new RecordingRegistrationPort()).signup(new SignupCommand(
                        "person@example.com", "too-short", UUID.randomUUID(), null)))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("15");
    }

    @Test
    void pendingEmailSignupReusesTheAccountAndIssuesANewVerificationToken() {
        UUID accountId = UUID.randomUUID();
        var commands = new RecordingRegistrationPort();
        var queries = new RegistrationQueryPort() {
            @Override
            public boolean emailExists(String emailLookupHmac) {
                return true;
            }

            @Override
            public Optional<ExistingEmailRegistration> findEmailRegistration(
                    List<IdentifierFingerprint> comparisonFingerprints) {
                return Optional.of(new ExistingEmailRegistration(
                        accountId,
                        AccountLifecycleStatus.PENDING_VERIFICATION,
                        EmailStatus.PENDING_VERIFICATION));
            }
        };
        var service = service(
                queries,
                commands,
                new AccountPreferenceDefaults("ko", "America/New_York", ThemePreference.SYSTEM));

        SignupResult result = service.signup(new SignupCommand(
                "person@example.com", "a sufficiently long passphrase", UUID.randomUUID(), "192.0.2.0/24"));

        assertThat(result.accountId()).isEqualTo(accountId);
        assertThat(result.verificationToken()).isEqualTo("raw-verification-token");
        assertThat(commands.registrations).isEmpty();
        assertThat(commands.pendingReplacements)
                .singleElement()
                .satisfies(replacement -> {
                    assertThat(replacement.accountId()).isEqualTo(accountId);
                    assertThat(replacement.tokenDigest()).isEqualTo("digest:raw-verification-token");
                    assertThat(replacement.password().encodedHash())
                            .isEqualTo("hash:a sufficiently long passphrase");
                });
    }

    @Test
    void activeEmailSignupRemainsAConflict() {
        var queries = new RegistrationQueryPort() {
            @Override
            public boolean emailExists(String emailLookupHmac) {
                return true;
            }

            @Override
            public Optional<ExistingEmailRegistration> findEmailRegistration(
                    List<IdentifierFingerprint> comparisonFingerprints) {
                return Optional.of(new ExistingEmailRegistration(
                        UUID.randomUUID(), AccountLifecycleStatus.ACTIVE, EmailStatus.VERIFIED));
            }
        };

        assertThatThrownBy(() -> service(
                        queries,
                        new RecordingRegistrationPort(),
                        new AccountPreferenceDefaults("ko", "America/New_York", ThemePreference.SYSTEM))
                .signup(new SignupCommand(
                        "person@example.com", "a sufficiently long passphrase", UUID.randomUUID(), null)))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void expiredAndConsumedVerificationTokensCannotActivateAccount() {
        var commands = new RecordingRegistrationPort();
        var service = service(false, commands);
        commands.verificationOutcome = VerificationOutcome.EXPIRED;

        assertThatThrownBy(() -> service.verify(new VerifyEmailCommand("expired-token", UUID.randomUUID())))
                .isInstanceOf(VerificationRejectedException.class)
                .hasMessage("Verification token has expired");

        commands.verificationOutcome = VerificationOutcome.ALREADY_USED;
        assertThatThrownBy(() -> service.verify(new VerifyEmailCommand("used-token", UUID.randomUUID())))
                .isInstanceOf(VerificationRejectedException.class)
                .hasMessage("Verification token is no longer valid");
    }

    @Test
    void resendReplacesPreviousTokenAndReturnsOnlyTheNewRawToken() {
        var commands = new RecordingRegistrationPort();
        var service = service(false, commands);
        UUID accountId = UUID.randomUUID();

        VerificationDelivery delivery = service.resendVerification(
                new ResendVerificationCommand(accountId, UUID.randomUUID(), "192.0.2.0/24"));

        assertThat(delivery.verificationToken()).isEqualTo("raw-verification-token");
        assertThat(commands.replacements)
                .singleElement()
                .extracting(VerificationReplacement::tokenDigest)
                .isEqualTo("digest:raw-verification-token");
    }

    private static EmailRegistrationService service(boolean duplicate, RecordingRegistrationPort commands) {
        return service(
                duplicate,
                commands,
                new AccountPreferenceDefaults("ko", "America/New_York", ThemePreference.SYSTEM));
    }

    private static EmailRegistrationService service(
            boolean duplicate,
            RecordingRegistrationPort commands,
            AccountPreferenceDefaults defaults) {
        return service(lookup -> duplicate, commands, defaults);
    }

    private static EmailRegistrationService service(
            RegistrationQueryPort queries,
            RecordingRegistrationPort commands,
            AccountPreferenceDefaults defaults) {
        return new EmailRegistrationService(
                queries,
                commands,
                raw -> new ProtectedEmail(
                        raw.trim().toLowerCase(),
                        "ciphertext:" + raw.trim().toLowerCase(),
                        "lookup:" + raw.trim().toLowerCase(),
                        (short) 1,
                        (short) 1),
                new NistPasswordPolicy(List.of("passwordpassword")),
                raw -> new PasswordHash("hash:" + raw, "TEST", "{}"),
                () -> new VerificationToken("raw-verification-token", "digest:raw-verification-token"),
                raw -> "digest:" + raw,
                defaults,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingRegistrationPort implements RegistrationCommandPort {
        private final List<PendingRegistration> registrations = new ArrayList<>();
        private final List<PendingRegistrationReplacement> pendingReplacements = new ArrayList<>();
        private final List<VerificationReplacement> replacements = new ArrayList<>();
        private VerificationOutcome verificationOutcome = VerificationOutcome.VERIFIED;

        @Override
        public void createPending(PendingRegistration registration) {
            registrations.add(registration);
        }

        @Override
        public VerificationOutcome consumeVerification(
                String tokenDigest, Instant consumedAt, UUID correlationId) {
            return verificationOutcome;
        }

        @Override
        public void replacePendingRegistration(PendingRegistrationReplacement replacement) {
            pendingReplacements.add(replacement);
        }

        @Override
        public void replaceVerification(VerificationReplacement replacement) {
            replacements.add(replacement);
        }
    }
}
