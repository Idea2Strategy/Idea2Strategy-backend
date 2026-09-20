package com.idea2strategy.backend.operatortrust;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.annotation.RequestScope;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorTrustProperties.class)
@ConditionalOnProperty(prefix = "idea2strategy.operator-auth", name = "enabled", havingValue = "true")
public class OperatorTrustModuleConfiguration {
    @Bean
    OperatorPasswordHasher operatorPasswordHasher(OperatorTrustProperties properties) {
        properties.validate();
        return new OperatorPasswordHasher(new OperatorPasswordHasher.Parameters(
                properties.getPasswordMemoryKb(), properties.getPasswordIterations(),
                properties.getPasswordParallelism(), 16, 32, 1));
    }

    @Bean
    OperatorSecretCipher operatorSecretCipher(OperatorTrustProperties properties) {
        var key = properties.getTotpEncryption();
        return new OperatorSecretCipher(key.getVersion(), Map.of(key.getVersion(),
                new SecretKeySpec(decode(key.getKey()), "AES")));
    }

    @Bean
    OperatorTokenProtector operatorTokenProtector(OperatorTrustProperties properties) {
        var versions = new EnumMap<OperatorTokenProtector.Domain, Integer>(OperatorTokenProtector.Domain.class);
        var rings = new EnumMap<OperatorTokenProtector.Domain, Map<Integer, byte[]>>(OperatorTokenProtector.Domain.class);
        put(versions, rings, OperatorTokenProtector.Domain.SESSION, properties.getSessionHmac());
        put(versions, rings, OperatorTokenProtector.Domain.CSRF, properties.getCsrfHmac());
        put(versions, rings, OperatorTokenProtector.Domain.SOURCE, properties.getSourceHmac());
        put(versions, rings, OperatorTokenProtector.Domain.LOGIN, properties.getLoginHmac());
        return new OperatorTokenProtector(versions, rings);
    }

    @Bean
    OperatorLoginThrottle operatorLoginThrottle(OperatorTrustProperties properties, ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        if (properties.getThrottleRedisUri() == null || properties.getThrottleRedisUri().isBlank()) {
            return new InMemoryOperatorLoginThrottle(clock, properties.getThrottleWindow(),
                    properties.getThrottleLoginLimit(), properties.getThrottleSourceLimit());
        }
        return new RedisOperatorLoginThrottle(properties.getThrottleRedisUri(), properties.getThrottleWindow(),
                properties.getThrottleLoginLimit(), properties.getThrottleSourceLimit());
    }

    @Bean
    OperatorSessionService operatorSessionService(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            OperatorPasswordHasher passwords, OperatorSecretCipher secrets,
            OperatorTokenProtector tokens, OperatorLoginThrottle throttle, ObjectProvider<Clock> clocks,
            OperatorTrustProperties properties) {
        return new OperatorSessionService(jdbc, transactionManager, passwords, new OperatorTotp(), secrets,
                tokens, throttle, clocks.getIfAvailable(Clock::systemUTC), properties.getIdleLifetime(),
                properties.getAbsoluteLifetime());
    }

    @Bean
    @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
    CurrentOperatorRbacContext currentOperatorRbacContext(
            HttpServletRequest request, OperatorSessionService sessions, OperatorTrustProperties properties) {
        return new ServletSessionOperatorRbacContext(request, sessions, properties.isSecureCookie());
    }

    @Bean
    OperatorCsrfFilter operatorCsrfFilter(OperatorSessionService sessions, OperatorTrustProperties properties) {
        return new OperatorCsrfFilter(sessions, properties.isSecureCookie());
    }

    private static void put(Map<OperatorTokenProtector.Domain, Integer> versions,
                            Map<OperatorTokenProtector.Domain, Map<Integer, byte[]>> rings,
                            OperatorTokenProtector.Domain domain, OperatorTrustProperties.VersionedKey key) {
        versions.put(domain, key.getVersion());
        rings.put(domain, Map.of(key.getVersion(), decode(key.getKey())));
    }

    private static byte[] decode(String encoded) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("OPERATOR_KEY_CONFIGURATION_INVALID"); }
        if (bytes.length != 32) throw new IllegalArgumentException("OPERATOR_KEY_CONFIGURATION_INVALID");
        return bytes;
    }
}
