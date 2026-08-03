package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.SessionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.web.context.annotation.RequestScope;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({SessionManagementService.class, HmacSessionTokens.class})
public class RequestPrincipalConfiguration {
    @Bean
    @RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
    BearerSessionCurrentPrincipal bearerSessionCurrentPrincipal(
            HttpServletRequest request,
            SessionManagementService sessions,
            HmacSessionTokens tokens) {
        return new BearerSessionCurrentPrincipal(request, sessions, tokens);
    }
}
