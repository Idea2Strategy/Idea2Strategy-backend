package com.idea2strategy.backend.api.operatorrbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacApiGuardCatalog;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandPort;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandService;
import com.idea2strategy.backend.persistence.operatorrbac.OperatorRbacPersistenceAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class OperatorRbacConfiguration {
    @Bean
    @ConditionalOnMissingBean(OperatorRbacCommandPort.class)
    OperatorRbacPersistenceAdapter operatorRbacPersistenceAdapter(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new OperatorRbacPersistenceAdapter(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(OperatorRbacCommandService.class)
    OperatorRbacCommandService operatorRbacCommandService(OperatorRbacCommandPort port) {
        return new OperatorRbacCommandService(port, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "idea2strategy.operator-rbac.guard",
            name = {"catalog-version", "grant-permission-id", "revoke-permission-id"})
    OperatorRbacApiGuardCatalog operatorRbacApiGuardCatalog(
            @Value("${idea2strategy.operator-rbac.guard.catalog-version}") String catalogVersion,
            @Value("${idea2strategy.operator-rbac.guard.grant-permission-id}") UUID grantPermissionId,
            @Value("${idea2strategy.operator-rbac.guard.revoke-permission-id}") UUID revokePermissionId) {
        var guard = new OperatorRbacApiGuardCatalog.Guard(
                catalogVersion, grantPermissionId, revokePermissionId);
        return () -> guard;
    }
}
