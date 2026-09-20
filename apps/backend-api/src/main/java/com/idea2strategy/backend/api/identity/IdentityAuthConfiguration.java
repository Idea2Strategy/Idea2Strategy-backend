package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.AccountPreferencesService;
import com.idea2strategy.backend.application.identity.AccountLifecycleCandidateQueryPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.application.identity.CustomerAccessValidationService;
import com.idea2strategy.backend.application.identity.LifecyclePasswordStepUpService;
import com.idea2strategy.backend.application.identity.LifecycleOidcStepUpService;
import com.idea2strategy.backend.application.identity.HmacOidcSubjectProtector;
import com.idea2strategy.backend.application.identity.OidcStepUpChallengeService;
import com.idea2strategy.backend.application.identity.OidcAuthenticationService;
import com.idea2strategy.backend.application.identity.NistPasswordPolicy;
import com.idea2strategy.backend.application.identity.PasswordRecoveryService;
import com.idea2strategy.backend.application.identity.PolicyConsentService;
import com.idea2strategy.backend.application.identity.RefreshTokenService;
import com.idea2strategy.backend.domain.identity.AccountPreferenceDefaults;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import com.idea2strategy.backend.persistence.identity.IdentityAccountJpaEntity;
import com.idea2strategy.backend.persistence.identity.AccountPreferencesConsentJpaAdapter;
import com.idea2strategy.backend.persistence.identity.AccountPreferencesConsentJooqAdapter;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJooqQueryAdapter;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJpaCommandAdapter;
import com.idea2strategy.backend.persistence.identity.IdentityJooqQueryAdapter;
import com.idea2strategy.backend.persistence.identity.IdentityJpaCommandAdapter;
import com.idea2strategy.backend.persistence.identity.OidcStepUpChallengeJpaAdapter;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrustedOidcProperties.class)
@ConditionalOnProperty(
        prefix = "identity.crypto",
        name = {"email-encryption-key", "lookup-hmac-key", "verification-hmac-key", "refresh-token-hmac-key",
                "customer-jwt-signing-key"})
@EntityScan(basePackageClasses = IdentityAccountJpaEntity.class)
@Import({
        IdentityJooqQueryAdapter.class,
        IdentityJpaCommandAdapter.class,
        AccountLifecycleJooqQueryAdapter.class,
        AccountLifecycleJpaCommandAdapter.class,
        AccountPreferencesConsentJooqAdapter.class,
        AccountPreferencesConsentJpaAdapter.class,
        OidcStepUpChallengeJpaAdapter.class,
        com.idea2strategy.backend.persistence.identity.DeviceAuthorizationJooqAdapter.class
})
public class IdentityAuthConfiguration {
    /**
     * How long a customer access token stays valid.
     *
     * <p>Five minutes was the value until 2026-08-09, and it did not survive first contact with real
     * use: a session that logged in and then worked through authoring, validation and release was
     * rejected mid-sequence, so a person doing one continuous task had to re-authenticate inside it.
     * That is a usability defect, not a security control — the token is a bearer credential whose real
     * revocation lever is the auth epoch and the credential version, both of which are checked on every
     * request and both of which take effect immediately regardless of this value. Shortening the token
     * only narrows the window for a stolen token that is not otherwise revoked, and an hour is the
     * ordinary trade for that.
     *
     * <p>Stated once, as a string constant, because {@code @Value} defaults must be compile-time
     * constants and this value used to be a literal repeated at each injection point. A test asserts
     * both constants and their relative order.
     */
    static final String DEFAULT_ACCESS_LIFETIME = "PT1H";

    /**
     * How long a refresh token family stays valid: 30 days, unchanged.
     *
     * <p>This is the value the access lifetime is measured against — it has to stay comfortably longer
     * than an access token, or a refresh would be pointless. It was already 720 hours, so raising the
     * access lifetime to an hour does not move it.
     */
    static final String DEFAULT_REFRESH_LIFETIME = "PT720H";

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    AesGcmEmailProtector emailProtector(
            @Value("${identity.crypto.email-encryption-key}") String encryptionKey,
            @Value("${identity.crypto.lookup-hmac-key}") String lookupKey,
            @Value("${identity.crypto.email-encryption-key-version:1}") short encryptionKeyVersion,
            @Value("${identity.crypto.lookup-hmac-key-version:1}") short lookupKeyVersion,
            @Value("${identity.crypto.previous-lookup-hmac-keys:}") String previousLookupKeys) {
        return new AesGcmEmailProtector(decode(encryptionKey), decode(lookupKey),
                encryptionKeyVersion, lookupKeyVersion, decodeKeyRing(previousLookupKeys));
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
    com.idea2strategy.backend.application.identity.DeviceAuthorizationService deviceAuthorizationService(
            com.idea2strategy.backend.persistence.identity.DeviceAuthorizationJooqAdapter adapter,
            HmacDeviceCodes deviceCodes,
            Clock identityClock,
            @Value("${identity.device-authorization.lifetime:PT10M}") Duration lifetime,
            @Value("${identity.device-authorization.poll-interval-seconds:5}") short pollIntervalSeconds) {
        return new com.idea2strategy.backend.application.identity.DeviceAuthorizationService(
                adapter, deviceCodes, identityClock, lifetime, pollIntervalSeconds);
    }

    /**
     * Falls back to the verification key so a deployment without a dedicated device-code key still
     * stores digests rather than raw codes. A separate key is preferable and is what the property
     * is for.
     */
    @Bean
    HmacDeviceCodes hmacDeviceCodes(
            @Value("${identity.crypto.device-code-hmac-key:${identity.crypto.verification-hmac-key}}") String key,
            @Value("${identity.crypto.device-code-key-version:1}") short keyVersion) {
        return new HmacDeviceCodes(decode(key), keyVersion);
    }

    @Bean
    HmacRefreshTokenSecrets refreshTokenSecrets(
            @Value("${identity.crypto.refresh-token-hmac-key}") String key) {
        return new HmacRefreshTokenSecrets(decode(key));
    }

    @Bean
    CustomerJwtCodec customerJwtCodec(
            @Value("${identity.crypto.customer-jwt-signing-key}") String key,
            Clock identityClock,
            @Value("${identity.jwt.issuer:https://ideatostrategy.com}") String issuer,
            @Value("${identity.jwt.access-audience:idea2strategy-api}") String accessAudience,
            @Value("${identity.jwt.refresh-audience:idea2strategy-refresh}") String refreshAudience,
            @Value("${identity.jwt.access-lifetime:" + DEFAULT_ACCESS_LIFETIME + "}") Duration accessLifetime) {
        return new CustomerJwtCodec(
                decode(key), identityClock, issuer, accessAudience, refreshAudience, accessLifetime);
    }

    @Bean
    RefreshTokenCookie refreshTokenCookie(
            Clock identityClock,
            @Value("${identity.jwt.refresh-cookie-secure:true}") boolean secure,
            @Value("${identity.jwt.refresh-cookie-same-site:Strict}") String sameSite) {
        return new RefreshTokenCookie(identityClock, secure, sameSite);
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
            @Value("${identity.preferences.default-timezone:America/New_York}") String defaultTimezone,
            @Value("${identity.email-verification-required:false}") boolean emailVerificationRequired) {
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
                emailVerificationRequired,
                identityClock);
    }

    @Bean
    EmailAuthenticationService emailAuthenticationService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            Pbkdf2PasswordCodec passwordCodec,
            AesGcmEmailProtector emailProtector,
            HmacRefreshTokenSecrets refreshTokenSecrets,
            Clock identityClock,
            @Value("${identity.jwt.refresh-lifetime:" + DEFAULT_REFRESH_LIFETIME + "}") Duration refreshLifetime) {
        return new EmailAuthenticationService(
                queries,
                commands,
                passwordCodec,
                emailProtector,
                refreshTokenSecrets,
                identityClock,
                refreshLifetime);
    }

    @Bean
    CustomerAccessValidationService customerAccessValidationService(IdentityJooqQueryAdapter queries) {
        return new CustomerAccessValidationService(queries);
    }

    @Bean
    RefreshTokenService refreshTokenService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            HmacRefreshTokenSecrets refreshTokenSecrets,
            Clock identityClock,
            @Value("${identity.jwt.refresh-lifetime:" + DEFAULT_REFRESH_LIFETIME + "}") Duration refreshLifetime) {
        return new RefreshTokenService(queries, commands, identityClock, refreshTokenSecrets, refreshLifetime);
    }

    @Bean
    AccountLifecycleService accountLifecycleService(
            AccountLifecycleCommandPort commands,
            AccountLifecycleCandidateQueryPort candidates,
            OidcStepUpChallengeJpaAdapter reactivationEligibility,
            Clock identityClock) {
        return new AccountLifecycleService(commands, candidates, reactivationEligibility, identityClock);
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
    HmacOidcStepUpNonces oidcStepUpNonces(
            @Value("${identity.crypto.oidc-nonce-hmac-key:${identity.crypto.verification-hmac-key}}") String key) {
        return new HmacOidcStepUpNonces(decode(key), (short) 1);
    }

    @Bean
    HmacOidcSubjectProtector oidcSubjectProtector(
            @Value("${identity.crypto.oidc-subject-hmac-key:${identity.crypto.lookup-hmac-key}}") String key,
            @Value("${identity.crypto.oidc-subject-hmac-key-version:1}") short keyVersion,
            @Value("${identity.crypto.previous-oidc-subject-hmac-keys:}") String previousKeys) {
        return new HmacOidcSubjectProtector(decode(key), keyVersion, decodeKeyRing(previousKeys));
    }

    @Bean
    OidcStepUpChallengeService oidcStepUpChallengeService(
            IdentityJooqQueryAdapter identities,
            OidcStepUpChallengeJpaAdapter challenges,
            HmacOidcStepUpNonces nonces,
            Clock identityClock,
            @Value("${identity.oidc.challenge-lifetime:PT5M}") Duration challengeLifetime) {
        return new OidcStepUpChallengeService(
                identities, challenges, nonces, identityClock, challengeLifetime);
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.oidc", name = "enabled", havingValue = "true")
    TrustedOidcIdTokenVerifier trustedOidcIdTokenVerifier(
            ObjectMapper objectMapper,
            Clock identityClock,
            TrustedOidcProperties properties,
            @Value("${identity.oidc.maximum-authentication-age:PT10M}") Duration maximumAuthenticationAge) {
        return new TrustedOidcIdTokenVerifier(
                objectMapper,
                HttpJwksSource.createDefault(),
                identityClock,
                maximumAuthenticationAge,
                properties.trustedProviders());
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.oidc", name = "enabled", havingValue = "true")
    OidcAuthenticationService oidcAuthenticationService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            HmacOidcSubjectProtector subjectProtector,
            HmacRefreshTokenSecrets refreshTokenSecrets,
            Clock identityClock,
            @Value("${identity.jwt.refresh-lifetime:" + DEFAULT_REFRESH_LIFETIME + "}") Duration refreshLifetime,
            AesGcmEmailProtector emailProtector,
            @Value("${identity.preferences.default-language:ko}") String defaultLanguage,
            @Value("${identity.preferences.default-timezone:America/New_York}") String defaultTimezone) {
        return new OidcAuthenticationService(
                queries,
                commands,
                subjectProtector,
                refreshTokenSecrets,
                identityClock,
                refreshLifetime,
                queries,
                commands,
                emailProtector,
                new AccountPreferenceDefaults(defaultLanguage, defaultTimezone, ThemePreference.SYSTEM));
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.oidc", name = "enabled", havingValue = "true")
    LifecycleOidcStepUpService lifecycleOidcStepUpService(
            IdentityJooqQueryAdapter identities,
            IdentityJpaCommandAdapter commands,
            OidcStepUpChallengeJpaAdapter challenges,
            HmacOidcStepUpNonces nonces,
            TrustedOidcIdTokenVerifier verifier,
            HmacOidcSubjectProtector subjectProtector,
            Clock identityClock) {
        return new LifecycleOidcStepUpService(
                identities, commands, challenges, nonces, verifier, subjectProtector, identityClock);
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

    private static Map<Short, byte[]> decodeKeyRing(String encoded) {
        var keys = new LinkedHashMap<Short, byte[]>();
        if (encoded == null || encoded.isBlank()) return Map.of();
        for (String entry : encoded.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("Previous HMAC keys must use version:base64 entries");
            }
            try {
                short version = Short.parseShort(parts[0]);
                if (keys.putIfAbsent(version, decode(parts[1])) != null) {
                    throw new IllegalStateException("Previous HMAC key versions must be unique");
                }
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Previous HMAC key version is invalid", exception);
            }
        }
        return Map.copyOf(keys);
    }
}
