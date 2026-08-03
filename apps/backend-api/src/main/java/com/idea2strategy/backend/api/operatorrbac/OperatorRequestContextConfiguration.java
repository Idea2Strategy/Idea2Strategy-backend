package com.idea2strategy.backend.api.operatorrbac;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.annotation.RequestScope;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "idea2strategy.operator-auth", name = "external-subject-hmac-key")
public class OperatorRequestContextConfiguration {
    @Bean
    @RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
    ServletOperatorRbacContext servletOperatorRbacContext(
            HttpServletRequest request,
            JdbcTemplate jdbc,
            @Value("${idea2strategy.operator-auth.external-subject-hmac-key}") String encodedKey,
            @Value("${idea2strategy.operator-auth.mfa-maximum-age:PT10M}") Duration maximumMfaAge) {
        return new ServletOperatorRbacContext(
                request, jdbc, Base64.getDecoder().decode(encodedKey), maximumMfaAge, Clock.systemUTC());
    }
}
