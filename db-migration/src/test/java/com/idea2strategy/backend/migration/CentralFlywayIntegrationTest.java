package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CentralFlywayIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void migratesOnceAndHasNoPendingWorkOnTheSecondRun() {
        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        int pendingBeforeMigration = flyway.info().pending().length;
        var first = flyway.migrate();
        var second = flyway.migrate();

        assertTrue(pendingBeforeMigration > 0);
        assertEquals(pendingBeforeMigration, first.migrationsExecuted);
        assertEquals(0, second.migrationsExecuted);
        assertTrue(flyway.validateWithResult().validationSuccessful);
    }
}
