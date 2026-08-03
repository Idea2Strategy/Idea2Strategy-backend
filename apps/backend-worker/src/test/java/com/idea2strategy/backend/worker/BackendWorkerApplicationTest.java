package com.idea2strategy.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.worker.outbox.OutboxRelayConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The worker's context with its real datasource and no queues configured.
 *
 * <p>The container is not decoration: the worker owns database duties, so a context that cannot reach
 * a database is not the one that runs in production. With no {@code outbox-relay.queues} entry the
 * relay must stay absent rather than wire against a guessed destination — a deployment that has not
 * been given a queue still has to start.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class BackendWorkerApplicationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutARelayWhenNoQueueIsConfigured() {
        assertThat(context.getBeanNamesForType(OutboxRelayConfiguration.OutboxRelayWorker.class))
                .isEmpty();
    }
}
