package com.idea2strategy.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.SchemaMappingType;
import org.jooq.meta.jaxb.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class JooqCodeGenerationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path generatedSources;

    @Test
    void generatesJooqTypesFromTheCentrallyMigratedSchema() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var database = new Database()
                .withName("org.jooq.meta.postgres.PostgresDatabase")
                .withIncludes(".*")
                .withSchemata(
                        new SchemaMappingType().withInputSchema("identity"),
                        new SchemaMappingType().withInputSchema("strategy"),
                        new SchemaMappingType().withInputSchema("bot"),
                        new SchemaMappingType().withInputSchema("storage"),
                        new SchemaMappingType().withInputSchema("market_data"),
                        new SchemaMappingType().withInputSchema("trading"),
                        new SchemaMappingType().withInputSchema("backtest"),
                        new SchemaMappingType().withInputSchema("performance"),
                        new SchemaMappingType().withInputSchema("competition"),
                        new SchemaMappingType().withInputSchema("operations"));
        var configuration = new org.jooq.meta.jaxb.Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(POSTGRES.getJdbcUrl())
                        .withUser(POSTGRES.getUsername())
                        .withPassword(POSTGRES.getPassword()))
                .withGenerator(new Generator()
                        .withDatabase(database)
                        .withTarget(new Target()
                                .withPackageName("com.idea2strategy.backend.jooq")
                                .withDirectory(generatedSources.toString())));

        GenerationTool.generate(configuration);

        assertThat(generatedSources.resolve(
                        "com/idea2strategy/backend/jooq/identity/tables/Accounts.java"))
                .exists();
        assertThat(generatedSources.resolve(
                        "com/idea2strategy/backend/jooq/strategy/tables/Strategies.java"))
                .exists();
        assertThat(generatedSources.resolve(
                        "com/idea2strategy/backend/jooq/market_data/tables/DatasetManifests.java"))
                .exists();
        assertThat(generatedSources.resolve(
                        "com/idea2strategy/backend/jooq/trading/tables/Orders.java"))
                .exists();
        assertThat(generatedSources.resolve(
                        "com/idea2strategy/backend/jooq/backtest/tables/Runs.java"))
                .exists();
    }
}
