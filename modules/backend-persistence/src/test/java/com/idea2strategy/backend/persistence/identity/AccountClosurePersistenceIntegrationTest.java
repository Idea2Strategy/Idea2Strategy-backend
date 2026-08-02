package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.accountclosure.AccountClosureCandidate;
import com.idea2strategy.backend.application.accountclosure.ClosureDomain;
import com.idea2strategy.backend.application.accountclosure.ClosureReadiness;
import com.idea2strategy.backend.application.accountclosure.ClosureReadinessStatus;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandType;
import com.idea2strategy.backend.application.identity.AccountLifecycleMutation;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AccountClosurePersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AccountClosurePersistenceIntegrationTest {
    private static final Instant REQUESTED = Instant.parse("2026-08-03T00:00:00Z");
    private static final Instant DEADLINE = REQUESTED.plusSeconds(30L * 24 * 3600);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired AccountLifecycleJpaCommandAdapter lifecycle;
    @Autowired AccountClosureJpaStore closure;
    @Autowired JdbcTemplate jdbc;

    @Test
    void closesOnlyWithFiveReadyBoundariesAndCreatesPolicySnapshotAndQuarantineAtomically() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", accountId);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        jdbc.update("""
                insert into identity.account_emails
                    (account_id, email_ciphertext, email_lookup_hmac, email_lookup_key_version,
                     encryption_key_version, status, verified_at)
                values (?, 'ciphertext', ?, 1, 1, 'VERIFIED', ?)
                """, accountId, "hmac-" + accountId, REQUESTED.atOffset(ZoneOffset.UTC));
        lifecycle.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "close-test", "request-hash", UUID.randomUUID(), ignored -> Optional.of(
                        new AccountLifecycleMutation(AccountLifecycleStatus.CLOSING, REQUESTED,
                                AccountLifecycleStatus.ACTIVE, REQUESTED, DEADLINE, "WITHDRAWAL_REQUESTED")));

        var candidate = new AccountClosureCandidate(accountId, DEADLINE, 2);
        UUID correlationId = UUID.randomUUID();
        for (var domain : ClosureDomain.values()) {
            closure.recordReadiness(accountId, correlationId,
                    new ClosureReadiness(domain, ClosureReadinessStatus.FROZEN, "READY", "{}", DEADLINE));
        }

        assertThat(closure.closeIfReady(candidate, correlationId, "close:" + accountId, DEADLINE)).isTrue();
        assertThat(jdbc.queryForObject(
                "select cast(lifecycle_status as text) from identity.accounts where id = ?",
                String.class, accountId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.account_retention_obligations where account_id = ?",
                Integer.class, accountId)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.account_identifier_quarantines where account_id = ?",
                Integer.class, accountId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select cast(status as text) from identity.account_emails where account_id = ?",
                String.class, accountId)).isEqualTo("REVOKED");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AccountLifecycleJpaCommandAdapter.class, AccountClosureJpaStore.class})
    static class TestApplication {}
}
