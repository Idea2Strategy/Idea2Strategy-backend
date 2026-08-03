package com.idea2strategy.backend.persistence.sanction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthorizationPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommand;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionResult;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AccountSanctionJdbcAdapterIntegrationTest.TestApplication.class)
class AccountSanctionJdbcAdapterIntegrationTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID OPERATOR = id(2);
    private static final UUID SANCTION = id(3);
    private static final UUID APPLY_PERMISSION = id(4);
    private static final UUID LIFT_PERMISSION = id(5);
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired AccountSanctionJdbcAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.execute("truncate table identity.account_sanction_command_receipts, "
                + "identity.account_sanction_events, identity.account_sanctions, "
                + "identity.account_sanction_heads cascade");
        jdbc.update("delete from operations.outbox_messages where owner_domain = 'ACCOUNT_SANCTION'");
        jdbc.update("delete from operations.audit_events where target_domain = 'ACCOUNT_SANCTION'");
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR);
        jdbc.update("delete from identity.accounts where id = ?", ACCOUNT);
        jdbc.update("insert into identity.accounts(id, lifecycle_status) values (?, 'ACTIVE')", ACCOUNT);
        jdbc.update("""
                insert into operations.operator_accounts
                    (id, external_identity_key_hmac, external_identity_key_version,
                     status, mfa_enrolled_at, created_at)
                values (?, ?, 1, 'ACTIVE', ?, ?)
                """, OPERATOR, "a".repeat(64), NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void concurrentDuplicateApplyCommitsOneHeadHistoryReceiptRevocationAuditAndOutboxSet() throws Exception {
        AccountSanctionCommandService service = service();
        AccountSanctionCommand command = new AccountSanctionCommand(
                AccountSanctionCommand.Type.APPLY,
                new OperatorRequestContext(OPERATOR, true, true),
                ACCOUNT, SANCTION, AccountSanctionState.Type.SUSPENSION, "POLICY_VIOLATION",
                NOW.plusSeconds(3600), null, id(6), "apply-once", "b".repeat(64), 0);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.execute(command));
            var second = executor.submit(() -> service.execute(command));
            AccountSanctionResult a = first.get(15, TimeUnit.SECONDS);
            AccountSanctionResult b = second.get(15, TimeUnit.SECONDS);
            assertThat(a).isEqualTo(b);
            assertThat(a.code()).isEqualTo("SANCTION_APPLIED");
        }

        assertThat(count("select count(*) from identity.account_sanctions")).isOne();
        assertThat(count("select count(*) from identity.account_sanction_events")).isOne();
        assertThat(count("select count(*) from identity.account_sanction_command_receipts")).isOne();
        assertThat(count("select count(*) from operations.audit_events where target_domain = 'ACCOUNT_SANCTION'"))
                .isOne();
        assertThat(count("select count(*) from operations.outbox_messages where owner_domain = 'ACCOUNT_SANCTION'"))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select auth_epoch from identity.account_security_states where account_id = ?",
                Long.class, ACCOUNT)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select public_reference from identity.account_sanctions where id = ?",
                UUID.class, SANCTION)).isNotNull();
        assertThatThrownBy(() -> jdbc.update(
                "update identity.account_sanction_events set reason_code = 'CHANGED' where sanction_id = ?",
                SANCTION)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private AccountSanctionCommandService service() {
        AccountSanctionAuthorizationPort authorization = (context, permission, at) ->
                new AccountSanctionAuthorizationPort.Decision(
                        true, "AUTHORIZED", "rbac-v1", Set.of(id(8)), Set.of(permission), true, true);
        return new AccountSanctionCommandService(
                adapter, authorization, adapter, adapter,
                APPLY_PERMISSION, LIFT_PERMISSION, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a1410000-0000-4000-8000-%012d".formatted(suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AccountSanctionJdbcAdapter.class)
    static class TestApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
