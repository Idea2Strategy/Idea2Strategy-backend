package com.idea2strategy.backend.persistence.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.notification.NotificationChannel;
import com.idea2strategy.backend.application.notification.NotificationQueryService;
import com.idea2strategy.backend.application.notification.NotificationRequest;
import com.idea2strategy.backend.application.notification.NotificationService;
import com.idea2strategy.backend.application.notification.NotificationUnavailableException;
import com.idea2strategy.backend.persistence.notification.EmailDeliveryGateway.DeliveryResult;
import com.idea2strategy.backend.persistence.notification.NotificationPersistenceAdapter.NotificationEvidenceConflictException;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
@SpringBootTest(classes = NotificationPersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationPersistenceAdapter adapter;
    @Autowired TransactionalOutboxStore outbox;
    @Autowired NotificationEmailWorker worker;
    @Autowired NotificationEventConsumer consumer;
    @Autowired FakeGateway gateway;

    @BeforeEach
    void clean() {
        jdbc.update("delete from operations.delivery_attempts");
        jdbc.update("delete from operations.outbox_delivery_attempts");
        jdbc.update("delete from operations.outbox_consumer_receipts");
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from operations.notifications");
        jdbc.update("delete from operations.notification_preferences");
        jdbc.update("delete from operations.notification_policies");
        gateway.results.clear();
    }

    @Test
    void persistsPolicyDecisionSnapshotAndSourceIdempotencyAndEnforcesOwnership() {
        UUID owner = account();
        UUID other = account();
        policy("ACCOUNT_SECURITY", "v3", true, "[\"APP\",\"EMAIL\"]");
        var service = service();
        var request = request(owner, "source-7", "hash-7");

        var created = service.create(request);
        var replayed = service.create(request);
        assertThat(replayed.notificationId()).isEqualTo(created.notificationId());
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.channels()).containsExactlyInAnyOrder(NotificationChannel.APP, NotificationChannel.EMAIL);
        assertThatThrownBy(() -> service.create(request(owner, "source-7", "different")))
                .isInstanceOf(NotificationEvidenceConflictException.class);

        var query = new NotificationQueryService(adapter);
        assertThat(query.list(owner, null, null, 20).items()).singleElement().satisfies(item -> {
            assertThat(item.templateVersion()).isEqualTo("template-v2");
            assertThat(item.locale()).isEqualTo("ko");
            assertThat(item.templateArguments()).containsEntry("subject", "security");
        });
        assertThat(query.list(other, null, null, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.markRead(other, created.notificationId()))
                .isInstanceOf(NotificationUnavailableException.class);
        service.markRead(owner, created.notificationId());
        assertThat(query.list(owner, null, null, 20).items().getFirst().readAt()).isNotNull();
        assertThat(count("select count(*) from operations.outbox_messages")).isEqualTo(1);
    }

    @Test
    void preferenceCanSuppressEmailButNeverTheOwnedInAppRecord() {
        UUID owner = account();
        policy("BOT_UPDATE", "v1", false, "[\"APP\",\"EMAIL\"]");
        jdbc.update("""
                insert into operations.notification_preferences
                    (account_id, event_type, channel, enabled, updated_at, policy_version)
                values (?, 'BOT_UPDATE', 'APP', true, clock_timestamp(), 'v1'),
                       (?, 'BOT_UPDATE', 'EMAIL', false, clock_timestamp(), 'v1')
                """, owner, owner);

        var receipt = service().create(new NotificationRequest(owner, "BOT_UPDATE", "template-v2", "ko",
                "bot-event", "bot-hash", Map.of(), UUID.randomUUID()));
        assertThat(receipt.channels()).containsExactly(NotificationChannel.APP);
        assertThat(count("select count(*) from operations.outbox_messages")).isZero();
    }

    @Test
    void fakeEmailDeliveryUsesA17RetryAndDeadLetterWhileAppendingAttempts() {
        UUID owner = account();
        policy("ACCOUNT_SECURITY", "v3", true, "[\"APP\",\"EMAIL\"]");
        UUID notificationId = service().create(request(owner, "source-worker", "hash-worker")).notificationId();
        gateway.results.add(DeliveryResult.retry("SMTP_TEMPORARY"));
        gateway.results.add(DeliveryResult.permanent("ADDRESS_REJECTED"));

        var first = outbox.claimDue("email-a", "email-policy-v1", Duration.ofSeconds(5), 1).getFirst();
        worker.deliver(first, "email-policy-v1", 2, Duration.ofSeconds(-1));
        assertThat(text("select delivery_status::text from operations.outbox_messages where id = ?", first.messageId()))
                .isEqualTo("PENDING");
        var second = outbox.claimDue("email-b", "email-policy-v1", Duration.ofSeconds(5), 1).getFirst();
        worker.deliver(second, "email-policy-v1", 2, Duration.ZERO);

        assertThat(text("select delivery_status::text from operations.outbox_messages where id = ?", second.messageId()))
                .isEqualTo("DEAD_LETTERED");
        assertThat(count("select count(*) from operations.delivery_attempts where notification_id = ?", notificationId))
                .isEqualTo(2);
        assertThat(count("select count(*) from operations.outbox_delivery_attempts where outbox_message_id = ?", second.messageId()))
                .isEqualTo(2);
    }

    @Test
    void sourceEventConsumerCompletesTheA17ReceiptInTheNotificationTransaction() {
        UUID owner = account();
        policy("ACCOUNT_SECURITY", "v3", true, "[\"APP\"]");
        UUID sourceId = UUID.randomUUID();
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, event_type, event_schema_version,
                     payload_document, idempotency_key)
                values (?, 'identity', ?, 'ACCOUNT_SECURITY_EVENT', '1', '{"kind":"security"}', ?)
                """, sourceId, owner, "security-source:" + sourceId);
        var source = outbox.claimDue("source-worker", "source-policy", Duration.ofSeconds(5), 1).getFirst();
        var request = request(owner, source.messageId().toString(), source.payloadHash());

        var first = consumer.consume(source, request, "notification-worker", Duration.ofSeconds(5));
        var replay = consumer.consume(source, request, "notification-worker", Duration.ofSeconds(5));

        assertThat(replay.notificationId()).isEqualTo(first.notificationId());
        assertThat(count("select count(*) from operations.notifications where source_event_id = ?",
                source.messageId().toString())).isEqualTo(1);
        assertThat(text("""
                select status::text from operations.outbox_consumer_receipts
                where consumer_handler_id = ? and outbox_message_id = ?
                """, NotificationEventConsumer.HANDLER_ID, source.messageId())).isEqualTo("COMPLETED");
    }

    private NotificationService service() {
        return new NotificationService(adapter, adapter, adapter,
                Clock.fixed(Instant.parse("2026-08-02T15:00:00Z"), ZoneOffset.UTC));
    }

    private NotificationRequest request(UUID owner, String eventId, String hash) {
        return new NotificationRequest(owner, "ACCOUNT_SECURITY", "template-v2", "ko",
                eventId, hash, Map.of("subject", "security"), UUID.randomUUID());
    }

    private void policy(String type, String version, boolean mandatory, String channels) {
        jdbc.update("""
                insert into operations.notification_policies
                    (type_code, policy_version, mandatory, default_channels, active, activated_at)
                values (?, ?, ?, cast(? as jsonb), true, clock_timestamp())
                """, type, version, mandatory, channels);
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", id);
        return id;
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }

    static final class FakeGateway implements EmailDeliveryGateway {
        final Queue<DeliveryResult> results = new ArrayDeque<>();
        @Override public DeliveryResult send(EmailMessage message) { return results.remove(); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({NotificationPersistenceAdapter.class, TransactionalOutboxStore.class,
            NotificationEmailWorker.class, NotificationEventConsumer.class, ObjectMapper.class})
    static class TestApplication {
        @Bean FakeGateway fakeGateway() { return new FakeGateway(); }
        @Bean Clock notificationClock() {
            return Clock.fixed(Instant.parse("2026-08-02T15:00:00Z"), ZoneOffset.UTC);
        }
    }
}
