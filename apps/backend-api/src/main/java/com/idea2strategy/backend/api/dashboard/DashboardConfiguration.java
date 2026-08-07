package com.idea2strategy.backend.api.dashboard;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.dashboard.DashboardQueryService;
import com.idea2strategy.backend.persistence.dashboard.DashboardJooqQueryAdapter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
@Import(DashboardJooqQueryAdapter.class)
public class DashboardConfiguration {
    @Bean
    DashboardQueryService dashboardQueryService(
            DashboardJooqQueryAdapter queryAdapter, CurrentPrincipal principal) {
        return new DashboardQueryService(queryAdapter, principal, Clock.systemUTC());
    }
}
