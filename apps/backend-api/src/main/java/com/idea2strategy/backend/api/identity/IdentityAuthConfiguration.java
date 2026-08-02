package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.AccountPreferencesService;
import com.idea2strategy.backend.application.identity.AccountLifecycleCandidateQueryPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.application.identity.LifecyclePasswordStepUpService;
import com.idea2strategy.backend.application.identity.NistPasswordPolicy;
import com.idea2strategy.backend.application.identity.PasswordRecoveryService;
import com.idea2strategy.backend.application.identity.PolicyConsentService;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import com.idea2strategy.backend.domain.identity.AccountPreferenceDefaults;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Duration;
import com.idea2strategy.backend.persistence.identity.IdentityAccountJpaEntity;
import com.idea2strategy.backend.persistence.identity.AccountPreferencesConsentJpaAdapter;
import com.idea2strategy.backend.persistence.identity.AccountPreferencesConsentJooqAdapter;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJooqQueryAdapter;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJpaCommandAdapter;
import com.idea2strategy.backend.persistence.identity.IdentityJooqQueryAdapter;
import com.idea2strategy.backend.persistence.identity.IdentityJpaCommandAdapter;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "identity.crypto",
        name = {"email-encryption-key", "lookup-hmac-key", "verification-hmac-key", "session-hmac-key"})
@EntityScan(basePackageClasses = IdentityAccountJpaEntity.class)
@Import({
        IdentityJooqQueryAdapter.class,
        IdentityJpaCommandAdapter.class,
        AccountLifecycleJooqQueryAdapter.class,
        AccountLifecycleJpaCommandAdapter.class,
        AccountPreferencesConsentJooqAdapter.class,
        AccountPreferencesConsentJpaAdapter.class
})
public class IdentityAuthConfiguration {
    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    AesGcmEmailProtector emailProtector(
            @Value("${identity.crypto.email-encryption-key}") String encryptionKey,
            @Value("${identity.crypto.lookup-hmac-key}") String lookupKey) {
        return new AesGcmEmailProtector(decode(encryptionKey), decode(lookupKey), (short) 1, (short) 1);
    }

    @Bean
    Pbkdf2PasswordCodec passwordCodec() {
        return new Pbkdf2PasswordCodec();
    }

    @Bean
    HmacVerificationTokens verificationTokens(
            @Value("${identity.crypto.verification-hmac-key}") String key) {
        return new HmacVerificationTokens(decode(key));
    }

    @Bean
    HmacSessionTokens sessionTokens(@Value("${identity.crypto.session-hmac-key}") String key) {
        return new HmacSessionTokens(decode(key));
    }

    @Bean
    HmacPasswordRecoveryTokens passwordRecoveryTokens(
            @Value("${identity.crypto.recovery-hmac-key:${identity.crypto.verification-hmac-key}}") String key) {
        return new HmacPasswordRecoveryTokens(decode(key));
    }

    @Bean
    VerificationDeliveryPort verificationDeliveryPort(ApplicationEventPublisher publisher) {
        return new ApplicationEventVerificationDelivery(publisher);
    }

    @Bean
    PasswordResetDeliveryPort passwordResetDeliveryPort(ApplicationEventPublisher publisher) {
        return new ApplicationEventPasswordResetDelivery(publisher);
    }

    @Bean
    PasswordRecoveryService passwordRecoveryService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            AesGcmEmailProtector emailProtector,
            Pbkdf2PasswordCodec passwordCodec,
            HmacPasswordRecoveryTokens recoveryTokens,
            Clock identityClock,
            @Value("${identity.recovery.reset-lifetime:PT30M}") Duration resetLifetime,
            @Value("${identity.recovery.code-count:10}") int recoveryCodeCount) {
        return new PasswordRecoveryService(
                queries,
                commands,
                emailProtector,
                new NistPasswordPolicy(List.of(
                        "passwordpassword",
                        "password123456",
                        "123456789012345",
                        "qwertyuiopasdfg")),
                passwordCodec,
                recoveryTokens,
                recoveryTokens,
                identityClock,
                resetLifetime,
                recoveryCodeCount);
    }

    @Bean
    EmailRegistrationService emailRegistrationService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            AesGcmEmailProtector emailProtector,
            Pbkdf2PasswordCodec passwordCodec,
            HmacVerificationTokens verificationTokens,
            Clock identityClock,
            @Value("${identity.preferences.default-language:ko}") String defaultLanguage,
            @Value("${identity.preferences.default-timezone:America/New_York}") String defaultTimezone) {
        return new EmailRegistrationService(
                queries,
                commands,
                emailProtector,
                new NistPasswordPolicy(List.of(
                        "passwordpassword",
                        "password123456",
                        "123456789012345",
                        "qwertyuiopasdfg")),
                passwordCodec,
                verificationTokens,
                verificationTokens,
                new AccountPreferenceDefaults(defaultLanguage, defaultTimezone, ThemePreference.SYSTEM),
                identityClock);
    }

    @Bean
    EmailAuthenticationService emailAuthenticationService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            Pbkdf2PasswordCodec passwordCodec,
            AesGcmEmailProtector emailProtector,
            HmacSessionTokens sessionTokens,
            Clock identityClock,
            @Value("${identity.session.lifetime:PT12H}") Duration sessionLifetime,
            @Value("${identity.session.max-active-sessions:5}") int maxActiveSessions) {
        return new EmailAuthenticationService(
                queries,
                commands,
                passwordCodec,
                emailProtector,
                sessionTokens,
                identityClock,
                sessionLifetime,
                maxActiveSessions);
    }

    @Bean
    SessionManagementService sessionManagementService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            HmacSessionTokens sessionTokens,
            Clock identityClock,
            @Value("${identity.session.lifetime:PT12H}") Duration sessionLifetime) {
        return new SessionManagementService(queries, commands, identityClock, sessionTokens, sessionLifetime);
    }

    @Bean
    AccountLifecycleService accountLifecycleService(
            AccountLifecycleCommandPort commands,
            AccountLifecycleCandidateQueryPort candidates,
            Clock identityClock) {
        return new AccountLifecycleService(commands, candidates, identityClock);
    }

    @Bean
    LifecyclePasswordStepUpService lifecyclePasswordStepUpService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            Pbkdf2PasswordCodec passwordCodec,
            AesGcmEmailProtector emailProtector,
            Clock identityClock) {
        return new LifecyclePasswordStepUpService(
                queries, commands, passwordCodec, emailProtector, identityClock);
    }

    @Bean
    AccountPreferencesService accountPreferencesService(
            AccountPreferencesConsentJooqAdapter queries,
            AccountPreferencesConsentJpaAdapter commands,
            Clock identityClock) {
        return new AccountPreferencesService(queries, commands, identityClock);
    }

    @Bean
    PolicyConsentService policyConsentService(
            AccountPreferencesConsentJooqAdapter queries,
            AccountPreferencesConsentJpaAdapter commands,
            Clock identityClock) {
        return new PolicyConsentService(queries, commands, identityClock);
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Identity crypto keys must use standard Base64 encoding", exception);
        }
    }
}
