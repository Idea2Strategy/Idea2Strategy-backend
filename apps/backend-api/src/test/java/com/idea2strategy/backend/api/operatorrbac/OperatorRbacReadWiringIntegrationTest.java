package com.idea2strategy.backend.api.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.api.BackendApiApplication;
import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.common.CurrentSessionPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BackendApiApplication.class, properties = {
        "idea2strategy.operator-rbac.read-guard.enabled=true",
        "idea2strategy.operator-rbac.read-guard.catalog-version=catalog-v1",
        "idea2strategy.operator-rbac.read-guard.catalog-read-permission-id=a2200000-0000-4000-8000-000000000003",
        "idea2strategy.operator-rbac.read-guard.assignment-read-permission-id=a2200000-0000-4000-8000-000000000004"
})
@Import(OperatorRbacReadWiringIntegrationTest.TrustStub.class)
@DirtiesContext
class OperatorRbacReadWiringIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired OperatorRbacReadController controller;

    @Test
    void enabledReadBoundaryStartsWithARepeatableReadTransactionProxy() {
        assertThat(AopUtils.isAopProxy(controller)).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TrustStub {
        @Bean CurrentOperatorRbacContext currentOperatorRbacContext() {
            return Optional::empty;
        }

        @Bean CurrentSessionPrincipal currentSessionPrincipal() {
            return new CurrentSessionPrincipal() {
                @Override public java.util.UUID accountId() { return new java.util.UUID(0, 1); }
                @Override public java.util.UUID sessionId() { return new java.util.UUID(0, 2); }
            };
        }
    }
}
