package com.idea2strategy.backend.persistence.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.notification.NotificationChannel;
import com.idea2strategy.backend.application.notification.NotificationQueryService;
import com.idea2strategy.backend.application.notification.NotificationRequest;
import com.idea2strategy.backend.application.usercase.UserCaseCommand;
import com.idea2strategy.backend.application.usercase.UserCaseEvidenceOwnershipPort;
import com.idea2strategy.backend.application.usercase.UserCaseService;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import com.idea2strategy.backend.persistence.notification.NotificationEventConsumer;
import com.idea2strategy.backend.persistence.notification.NotificationPersistenceAdapter;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.usercase.UserCaseJooqStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AccountOperationsPersistenceJourneyIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountOperationsPersistenceJourneyIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T02:00:00Z");
    private static final UUID ACCOUNT = id(1);
    private static final UUID CORRELATION = id(2);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserCaseJooqStore cases;
    @Autowired TransactionalOutboxStore outbox;
    @Autowired NotificationPersistenceAdapter notifications;
    @Autowired NotificationEventConsumer notificationConsumer;
    @Autowired Clock clock;

    @Test
    void commitsCaseOutboxConsumerReceiptAndOwnedNotificationAsOneReplayableJourney() {
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", ACCOUNT);
        jdbc.update("""
                insert into operations.notification_policies
                    (type_code, policy_version, mandatory, default_channels, active, activated_at)
                values ('CASE_UPDATE', 'journey-v1', true, '["APP"]'::jsonb, true, clock_timestamp())
                """);

        var caseService = new UserCaseService(cases, clock);
        UserCaseCommand command = new UserCaseCommand(
                ACCOUNT, UserCaseType.REPORT, "Order review", "Please review the attached order state",
                List.of(), "journey-case-submit", "a".repeat(64), CORRELATION);
        var firstCase = caseService.submit(command);
        var replayedCase = caseService.submit(command);

        assertThat(replayedCase).isEqualTo(firstCase);
        assertThat(caseService.detail(ACCOUNT, firstCase.id())).isEqualTo(firstCase);
        assertThat(jdbc.queryForObject("select count(*) from operations.cases", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from operations.case_events", Integer.class)).isOne();

        var source = outbox.claimDue("a22-source", "a22-policy-v1", Duration.ofSeconds(30), 1).getFirst();
        assertThat(source.ownerDomain()).isEqualTo("OPERATIONS_CASE");
        assertThat(source.eventType()).isEqualTo("USER_CASE_SUBMITTED");

        NotificationRequest notification = new NotificationRequest(
                ACCOUNT, "CASE_UPDATE", "case-update-v1", "ko",
                source.messageId().toString(), source.payloadHash(),
                Map.of("caseId", firstCase.id().toString(), "status", firstCase.status().name()),
                CORRELATION);
        var created = notificationConsumer.consume(
                source, notification, "a22-notification", Duration.ofSeconds(30));
        var replayed = notificationConsumer.consume(
                source, notification, "a22-notification", Duration.ofSeconds(30));
        outbox.acknowledge(source.messageId(), source.claimToken(), "a22-transport-1");

        assertThat(replayed.notificationId()).isEqualTo(created.notificationId());
        assertThat(replayed.replayed()).isTrue();
        assertThat(created.channels()).containsExactly(NotificationChannel.APP);
        assertThat(new NotificationQueryService(notifications)
                .list(ACCOUNT, null, null, 10).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.typeCode()).isEqualTo("CASE_UPDATE");
                    assertThat(item.templateArguments()).containsEntry("caseId", firstCase.id().toString());
                });
        assertThat(jdbc.queryForObject("""
                select count(*) from operations.outbox_consumer_receipts
                where outbox_message_id = ? and status = 'COMPLETED'
                """, Integer.class, source.messageId())).isOne();
        assertThat(jdbc.queryForObject("""
                select delivery_status::text from operations.outbox_messages where id = ?
                """, String.class, source.messageId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select count(*) from operations.notifications", Integer.class)).isOne();
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2200000-0000-4000-8000-" + "%012d".formatted(suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({UserCaseJooqStore.class, TransactionalOutboxStore.class,
            NotificationPersistenceAdapter.class, NotificationEventConsumer.class})
    static class TestApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        Clock journeyClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        UserCaseEvidenceOwnershipPort evidenceOwnership() {
            return (accountId, evidence, at) -> java.util.Optional.empty();
        }
    }
}
