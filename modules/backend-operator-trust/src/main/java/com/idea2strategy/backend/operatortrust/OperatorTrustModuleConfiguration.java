package com.idea2strategy.backend.operatortrust;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.context.annotation.RequestScope;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorTrustProperties.class)
@ConditionalOnProperty(
        prefix = "idea2strategy.operator-auth", name = "enabled", havingValue = "true")
public class OperatorTrustModuleConfiguration {
    @Bean
    OperatorTrustConfiguration operatorTrustConfiguration(OperatorTrustProperties properties) {
        return properties.validated();
    }

    @Bean
    JwtDecoder operatorJwtDecoder(
            OperatorTrustConfiguration configuration, ObjectProvider<Clock> clocks) {
        return OperatorJwtDecoderFactory.create(
                configuration, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    OperatorJwtAssurance operatorJwtAssurance(
            OperatorTrustConfiguration configuration, ObjectProvider<Clock> clocks) {
        return new OperatorJwtAssurance(
                configuration, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    VersionedOperatorSubjectHmac versionedOperatorSubjectHmac(
            OperatorTrustConfiguration configuration) {
        return new VersionedOperatorSubjectHmac(configuration);
    }

    @Bean
    JdbcOperatorIdentityResolver jdbcOperatorIdentityResolver(
            JdbcTemplate jdbc, VersionedOperatorSubjectHmac subjects) {
        return new JdbcOperatorIdentityResolver(jdbc, subjects);
    }

    @Bean
    OperatorAuthenticationEventSink operatorAuthenticationEventSink() {
        return new Slf4jOperatorAuthenticationEventSink();
    }

    @Bean
    OperatorBearerAuthenticationService operatorBearerAuthenticationService(
            @Qualifier("operatorJwtDecoder") JwtDecoder decoder,
            OperatorJwtAssurance assurance,
            JdbcOperatorIdentityResolver identities,
            OperatorAuthenticationEventSink events) {
        return new OperatorBearerAuthenticationService(decoder, assurance, identities, events);
    }

    @Bean
    @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
    CurrentOperatorRbacContext currentOperatorRbacContext(
            HttpServletRequest request,
            OperatorBearerAuthenticationService authentication,
            OperatorAuthenticationEventSink events) {
        return new ServletBearerOperatorRbacContext(request, authentication, events);
    }
}
