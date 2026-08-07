package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationMethod;
import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationProof;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommand;
import com.idea2strategy.backend.application.identity.AccountLifecycleRejectedException;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import com.idea2strategy.backend.application.identity.IdentityCommandPort;
import com.idea2strategy.backend.application.identity.LifecycleOidcStepUpService;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerifier;
import com.idea2strategy.backend.application.identity.OidcIdentityQueryPort;
import com.idea2strategy.backend.application.identity.OidcProvider;
import com.idea2strategy.backend.application.identity.OidcStepUpNonceSupport;
import com.idea2strategy.backend.application.identity.OidcSubjectProtector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AccountReactivationPersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AccountReactivationPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");
    private static final UUID POLICY_ID = UUID.fromString("13000000-0000-4000-8000-000000000010");
    private static final UUID OPERATOR_ID = UUID.fromString("13000000-0000-4000-8000-000000000011");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired private AccountLifecycleJpaCommandAdapter commands;
    @Autowired private AccountLifecycleJooqQueryAdapter candidates;
    @Autowired private OidcStepUpChallengeJpaAdapter eligibility;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void consumesTheOidcNonceAndCommitsOneReactivationHeadAndReceipt() {
        UUID accountId = dormantAccount(true);
        UUID challengeId = challenge();
        var service = service();
        var command = command(accountId, challengeId, "reactivate-1");
        long sessionsBefore = jdbc.queryForObject(
                "select count(*) from identity.refresh_token_families where account_id = ?", Long.class, accountId);

        var first = service.reactivate(command);
        var replay = service.reactivate(command);

        assertThat(first.status()).isEqualTo(AccountLifecycleStatus.ACTIVE);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.refresh_token_families where account_id = ?", Long.class, accountId))
                .isEqualTo(sessionsBefore);
        assertThat(consentCount(accountId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                        select cast(account.lifecycle_status as text) as status,
                               account.lifecycle_version, event.command_type, event.new_status,
                               receipt.response_status
                        from identity.accounts account
                        join identity.account_lifecycle_events event
                          on event.id = account.last_lifecycle_event_id
                        join identity.account_lifecycle_command_receipts receipt
                          on receipt.lifecycle_event_id = event.id
                        where account.id = ?
                        """, accountId))
                .containsEntry("status", "ACTIVE")
                .containsEntry("lifecycle_version", 2L)
                .containsEntry("command_type", "ACCOUNT_REACTIVATED")
                .satisfies(row -> assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(200));
        assertThat(jdbc.queryForMap("""
                        select consumed_at, consumed_by_account_id
                        from identity.oidc_step_up_nonces where id = ?
                        """, challengeId))
                .containsEntry("consumed_by_account_id", accountId)
                .satisfies(row -> assertThat(row.get("consumed_at")).isNotNull());
    }

    @Test
    void replaysTheReceiptFromAConsumedChallengeWithoutJwtOrJwksButRejectsNewOrChangedCommands() {
        UUID accountId = dormantAccount(true);
        UUID challengeId = challenge();
        var lifecycle = service();
        var original = command(accountId, challengeId, "receipt-replay");
        var first = lifecycle.reactivate(original);
        eligibility.create(
                UUID.randomUUID(), (short) 130, "later-digest:" + UUID.randomUUID(), (short) 1,
                NOW.plusSeconds(3600), NOW.plusSeconds(3900));

        OidcIdentityQueryPort identities = mock(OidcIdentityQueryPort.class);
        when(identities.findProvider("TEST_OIDC")).thenReturn(Optional.of(
                new OidcProvider((short) 130, "TEST_OIDC", "https://issuer.test", true)));
        OidcIdTokenVerifier unavailableVerifier = mock(OidcIdTokenVerifier.class);
        var oidc = new LifecycleOidcStepUpService(
                identities,
                mock(IdentityCommandPort.class),
                eligibility,
                mock(OidcStepUpNonceSupport.class),
                unavailableVerifier,
                mock(OidcSubjectProtector.class),
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));

        var replayProof = oidc.authenticate(
                "TEST_OIDC", "expired.jwt.unavailable-jwks", challengeId, UUID.randomUUID()).proof();
        verify(unavailableVerifier, never()).verify(any());
        assertThat(lifecycle.reactivate(new AccountLifecycleCommand(
                        accountId, original.idempotencyKey(), original.requestHash(), UUID.randomUUID(), replayProof)))
                .isEqualTo(first);
        assertThatThrownBy(() -> lifecycle.reactivate(new AccountLifecycleCommand(
                        accountId, "new-key", original.requestHash(), UUID.randomUUID(), replayProof)))
                .isInstanceOf(AccountLifecycleRejectedException.class);
        assertThatThrownBy(() -> lifecycle.reactivate(new AccountLifecycleCommand(
                        accountId, original.idempotencyKey(), "different-hash", UUID.randomUUID(), replayProof)))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void challengeIssuanceCleansExpiredPendingRows() {
        seedOidcProvider();
        UUID expired = UUID.randomUUID();
        eligibility.create(
                expired, (short) 130, "expired-digest:" + expired, (short) 1,
                NOW.minusSeconds(20), NOW.minusSeconds(10));
        eligibility.create(
                UUID.randomUUID(), (short) 130, "fresh-digest:" + UUID.randomUUID(), (short) 1,
                NOW, NOW.plusSeconds(300));

        assertThat(jdbc.queryForObject(
                "select count(*) from identity.oidc_step_up_nonces where id = ?", Long.class, expired))
                .isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void atomicallyCapsConcurrentVerificationAttemptsAtFive() throws Exception {
        seedOidcProvider();
        UUID challengeId = UUID.randomUUID();
        eligibility.create(
                challengeId, (short) 130, "attempt-cap:" + challengeId, (short) 1,
                NOW.minusSeconds(1), NOW.plusSeconds(300));
        var executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(ignored -> (Callable<Boolean>) () ->
                            eligibility.registerVerificationAttempt(challengeId, NOW))
                    .toList();
            long accepted = executor.invokeAll(attempts).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();

            assertThat(accepted).isEqualTo(5);
            assertThat(jdbc.queryForObject("""
                            select verification_attempt_count
                            from identity.oidc_step_up_nonces where id = ?
                            """, Integer.class, challengeId))
                    .isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesConcurrentIssuanceAtTheProviderPendingChallengeBoundary() throws Exception {
        seedOidcProvider();
        jdbc.update("delete from identity.oidc_step_up_nonces where provider_id = 130");
        jdbc.update("""
                insert into identity.oidc_step_up_nonces
                    (id, provider_id, nonce_digest, digest_key_version, requested_at, expires_at)
                select gen_random_uuid(), 130, 'capacity-' || value, 1, ?, ?
                from generate_series(1, 9999) value
                """, utc(NOW), utc(NOW.plusSeconds(300)));
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> issuances = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> (Callable<Boolean>) () -> {
                        try {
                            UUID id = UUID.randomUUID();
                            eligibility.create(
                                    id, (short) 130, "boundary-" + id, (short) 1,
                                    NOW, NOW.plusSeconds(300));
                            return true;
                        } catch (com.idea2strategy.backend.application.identity.AuthenticationRejectedException rejected) {
                            return false;
                        }
                    })
                    .toList();
            long accepted = executor.invokeAll(issuances).stream().filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();
            assertThat(accepted).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                            select count(*) from identity.oidc_step_up_nonces
                            where provider_id = 130 and consumed_at is null
                            """, Long.class))
                    .isEqualTo(10_000L);
        } finally {
            executor.shutdownNow();
            jdbc.update("delete from identity.oidc_step_up_nonces where provider_id = 130");
        }
    }

    @Test
    void allowsNoGlobalRequiredPoliciesButFailsClosedWhenRequiredPolicyHasNoAcceptedLanguageOrFallback() {
        seedOidcProvider();
        UUID noPolicyAccount = dormantAccountWithoutPreferences();
        UUID noPolicyChallenge = challenge();
        assertThat(service().reactivate(command(
                        noPolicyAccount, noPolicyChallenge, "no-global-policy", Set.of())).status())
                .isEqualTo(AccountLifecycleStatus.ACTIVE);

        UUID fallbackAccepted = dormantAccount(true);
        jdbc.update("update identity.account_preferences set language_code = 'ja' where account_id = ?", fallbackAccepted);
        assertThat(service().reactivate(command(fallbackAccepted, challenge(), "accepted-ko-fallback")).status())
                .isEqualTo(AccountLifecycleStatus.ACTIVE);

        UUID unsupportedLanguage = dormantAccount(false);
        jdbc.update("update identity.account_preferences set language_code = 'ja' where account_id = ?", unsupportedLanguage);
        UUID requiredChallenge = challenge();
        assertThatThrownBy(() -> service().reactivate(
                        command(unsupportedLanguage, requiredChallenge, "missing-language-fallback-acceptance", Set.of())))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("CURRENT_REQUIRED_CONSENT_MISSING");
    }

    @Test
    void keepsDormantWhenCurrentRequiredConsentIsMissingOrASanctionIsEffective() {
        UUID missingConsent = dormantAccount(false);
        UUID missingChallenge = challenge();
        assertThatThrownBy(() -> service().reactivate(
                        command(missingConsent, missingChallenge, "missing-consent", Set.of())))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("CURRENT_REQUIRED_CONSENT_MISSING");
        assertDormantAndNonceUnused(missingConsent, missingChallenge);
        assertThat(consentCount(missingConsent)).isZero();

        UUID sanctioned = dormantAccount(true);
        UUID sanctionChallenge = challenge();
        insertEffectiveSanction(sanctioned);
        assertThatThrownBy(() -> service().reactivate(command(sanctioned, sanctionChallenge, "sanctioned")))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("ACCOUNT_REACTIVATION_RESTRICTED");
        assertDormantAndNonceUnused(sanctioned, sanctionChallenge);
    }

    @Test
    void atomicallyRecordsMissingConsentWithReactivationAndRejectsExtraOrStaleSetsWithoutWrites() {
        UUID accountId = dormantAccount(false);
        var acceptedCommand = command(accountId, challenge(), "accept-and-reactivate");
        var result = service().reactivate(acceptedCommand);
        assertThat(result.status()).isEqualTo(AccountLifecycleStatus.ACTIVE);
        assertThat(service().reactivate(acceptedCommand)).isEqualTo(result);
        assertThat(consentCount(accountId)).isEqualTo(1);

        UUID extraAccount = dormantAccount(false);
        assertThatThrownBy(() -> service().reactivate(command(
                        extraAccount, challenge(), "extra-policy", Set.of(POLICY_ID, UUID.randomUUID()))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("CURRENT_REQUIRED_CONSENT_MISSING");
        assertThat(consentCount(extraAccount)).isZero();

        UUID newerPolicy = UUID.randomUUID();
        jdbc.update("""
                insert into identity.policy_documents
                    (id, policy_code, version, language_code, title, content_format,
                     content_text, content_hash, is_required, published_at)
                values (?, 'TERMS', '2', 'ko', 'Terms v2', 'TEXT', 'terms-v2', ?, true, ?)
                """, newerPolicy, "b".repeat(64), utc(NOW.minusSeconds(10)));
        UUID staleAccount = dormantAccount(false);
        assertThatThrownBy(() -> service().reactivate(command(staleAccount, challenge(), "stale-policy")))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("CURRENT_REQUIRED_CONSENT_MISSING");
        assertThat(consentCount(staleAccount)).isZero();

        jdbc.update("update identity.policy_documents set retired_at = ? where id = ?",
                utc(NOW.minusSeconds(1)), newerPolicy);
        UUID retiredAccount = dormantAccount(false);
        assertThatThrownBy(() -> service().reactivate(command(
                        retiredAccount, challenge(), "retired-policy", Set.of(newerPolicy))))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("CURRENT_REQUIRED_CONSENT_MISSING");
        assertThat(consentCount(retiredAccount)).isZero();
    }

    private AccountLifecycleService service() {
        return new AccountLifecycleService(
                commands, candidates, eligibility, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AccountLifecycleCommand command(UUID accountId, UUID challengeId, String key) {
        return command(accountId, challengeId, key, Set.of(POLICY_ID));
    }

    private AccountLifecycleCommand command(
            UUID accountId, UUID challengeId, String key, Set<UUID> acceptedPolicyDocumentIds) {
        var proof = new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.OIDC,
                accountId,
                "TEST_OIDC",
                challengeId,
                NOW.minusSeconds(60),
                NOW,
                true);
        return new AccountLifecycleCommand(
                accountId, key, "hash:" + key, UUID.randomUUID(), proof, acceptedPolicyDocumentIds);
    }

    private long consentCount(UUID accountId) {
        return jdbc.queryForObject(
                "select count(*) from identity.account_consents where account_id = ?", Long.class, accountId);
    }

    private UUID dormantAccount(boolean acceptedConsent) {
        seedReferences();
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                insert into identity.accounts
                    (id, lifecycle_status, lifecycle_version, last_successful_auth_at, dormant_at)
                values (?, cast('DORMANT' as identity.account_lifecycle_status), 1, ?, ?)
                """, accountId, utc(NOW.minusSeconds(400L * 24 * 3600)), utc(NOW.minusSeconds(60)));
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        jdbc.update("""
                insert into identity.account_preferences
                    (account_id, language_code, timezone_name, theme_preference)
                values (?, 'ko', 'Asia/Seoul', 'SYSTEM')
                """, accountId);
        if (acceptedConsent) {
            jdbc.update("""
                    insert into identity.account_consents
                        (id, account_id, policy_document_id, decision, recorded_at)
                    values (?, ?, ?, cast('ACCEPTED' as identity.consent_decision), ?)
                    """, UUID.randomUUID(), accountId, POLICY_ID, utc(NOW.minusSeconds(30)));
        }
        return accountId;
    }

    private UUID dormantAccountWithoutPreferences() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                insert into identity.accounts
                    (id, lifecycle_status, lifecycle_version, last_successful_auth_at, dormant_at)
                values (?, cast('DORMANT' as identity.account_lifecycle_status), 1, ?, ?)
                """, accountId, utc(NOW.minusSeconds(400L * 24 * 3600)), utc(NOW.minusSeconds(60)));
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        return accountId;
    }

    private UUID challenge() {
        UUID id = UUID.randomUUID();
        eligibility.create(id, (short) 130, "digest:" + id, (short) 1, NOW.minusSeconds(10), NOW.plusSeconds(300));
        return id;
    }

    private void seedReferences() {
        seedOidcProvider();
        jdbc.update("""
                insert into identity.policy_documents
                    (id, policy_code, version, language_code, title, content_format,
                     content_text, content_hash, is_required, published_at)
                values (?, 'TERMS', '1', 'ko', 'Terms', 'TEXT', 'terms', ?, true, ?)
                on conflict (id) do nothing
                """, POLICY_ID, "a".repeat(64), utc(NOW.minusSeconds(3600)));
        jdbc.update("""
                insert into operations.operator_accounts
                    (id, external_identity_key_hmac, external_identity_key_version,
                     status, mfa_enrolled_at, created_at)
                values (?, ?, 1, 'ACTIVE', ?, ?)
                on conflict (id) do nothing
                """, OPERATOR_ID, "operator-130", utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(3600)));
    }

    private void seedOidcProvider() {
        jdbc.update("""
                insert into identity.auth_providers
                    (id, code, display_name, provider_type, issuer)
                values (130, 'TEST_OIDC', 'Test OIDC', cast('OIDC' as identity.auth_provider_type), 'https://issuer.test')
                on conflict (id) do nothing
                """);
    }

    private void insertEffectiveSanction(UUID accountId) {
        jdbc.update("""
                insert into identity.account_sanctions
                    (id, account_id, sanction_type, status, reason_code, applied_by_operator_id,
                     applied_at, effective_at, expires_at, status_changed_at)
                values (?, ?, 'SUSPENSION', cast('ACTIVE' as identity.sanction_status), 'RISK_REVIEW', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), accountId, OPERATOR_ID,
                utc(NOW.minusSeconds(120)), utc(NOW.minusSeconds(60)), utc(NOW.plusSeconds(3600)),
                utc(NOW.minusSeconds(60)));
    }

    private void assertDormantAndNonceUnused(UUID accountId, UUID challengeId) {
        assertThat(jdbc.queryForObject(
                "select cast(lifecycle_status as text) from identity.accounts where id = ?",
                String.class,
                accountId)).isEqualTo("DORMANT");
        assertThat(jdbc.queryForObject(
                "select consumed_at is null from identity.oidc_step_up_nonces where id = ?",
                Boolean.class,
                challengeId)).isTrue();
    }

    private static java.time.OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AccountLifecycleJpaCommandAdapter.class,
            AccountLifecycleJooqQueryAdapter.class,
            OidcStepUpChallengeJpaAdapter.class
    })
    static class TestApplication {}
}
