package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.NistPasswordPolicy;
import com.idea2strategy.backend.persistence.identity.IdentityAccountJpaEntity;
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

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "identity.crypto",
        name = {"email-encryption-key", "lookup-hmac-key", "verification-hmac-key", "session-hmac-key"})
@EntityScan(basePackageClasses = IdentityAccountJpaEntity.class)
@Import({IdentityJooqQueryAdapter.class, IdentityJpaCommandAdapter.class})
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
    EmailRegistrationService emailRegistrationService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            AesGcmEmailProtector emailProtector,
            Pbkdf2PasswordCodec passwordCodec,
            HmacVerificationTokens verificationTokens,
            Clock identityClock) {
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
                identityClock);
    }

    @Bean
    EmailAuthenticationService emailAuthenticationService(
            IdentityJooqQueryAdapter queries,
            IdentityJpaCommandAdapter commands,
            Pbkdf2PasswordCodec passwordCodec,
            AesGcmEmailProtector emailProtector,
            HmacSessionTokens sessionTokens,
            Clock identityClock) {
        return new EmailAuthenticationService(
                queries, commands, passwordCodec, emailProtector, sessionTokens, identityClock);
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Identity crypto keys must use standard Base64 encoding", exception);
        }
    }
}
