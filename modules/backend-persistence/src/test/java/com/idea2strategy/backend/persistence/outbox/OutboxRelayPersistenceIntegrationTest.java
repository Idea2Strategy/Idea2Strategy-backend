package com.idea2strategy.backend.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.outbox.OutboxMessagePublisher;
import com.idea2strategy.backend.application.outbox.PublishableOutboxMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The relay's claim protocol against the real canonical outbox.
 *
 * <p>The publisher is a recorder here on purpose: what needs proving is the state machine — that a
 * message is published once, that producer order survives, that a transport failure retries on its
 * backoff and then dead-letters, and that a replica whose lease lapsed is taken over rather than
 * stalling the backlog. The SQS publisher itself is proven separately against LocalStack.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OutboxRelayPersistenceIntegrationTest.TestApplication.class)
class OutboxRelayPersistenceIntegrationTest {

    private static final UUID BOT = UUID.fromString("11000000-0000-4000-8000-0000000000a1");
    private static final Instant T0 = Instant.parse("2026-08-03T15:00:00Z");
    private static final String ROUTED = "OFFICIAL_BACKTEST_REQUESTED";
    private static final String UNROUTED = "ACCOUNT_SANCTIONED";

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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcTemplate jdbc;

    private RecordingPublisher publisher;
    private MutableClock clock;
    private OutboxRelay relay;

    @BeforeEach
    void prepareRelay() {
        jdbc.update("delete from operations.outbox_messages");
        publisher = new RecordingPublisher();
        clock = new MutableClock(T0);
        relay = new OutboxRelay(
                jdbcClient, publisher, clock, "relay-test", Set.of(ROUTED),
                10, Duration.ofSeconds(30), Duration.ofSeconds(60), 3);
    }

    @Test
    void publishesEachRoutedMessageOnceAndLeavesOthersAlone() {
        seed(1, ROUTED);
        clock.advance(Duration.ofSeconds(1));
        seed(2, ROUTED);
        seed(3, UNROUTED);

        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(publisher.published).hasSize(2);
        assertThat(status(1)).isEqualTo("PUBLISHED");
        assertThat(status(2)).isEqualTo("PUBLISHED");
        // A relay only owns the event types routed to it; another transport's rows stay PENDING.
        assertThat(status(3)).isEqualTo("PENDING");

        clock.advance(Duration.ofSeconds(120));
        assertThat(relay.relayOnce()).isZero();
        assertThat(publisher.published).hasSize(2);
    }

    /** Producer order survives the claim, which `UPDATE ... RETURNING` alone does not guarantee. */
    @Test
    void publishesInProducerOrder() {
        for (int sequence = 1; sequence <= 5; sequence++) {
            seed(sequence, ROUTED);
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(relay.relayOnce()).isEqualTo(5);
        assertThat(publisher.published.stream().map(PublishableOutboxMessage::aggregateSequence).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    /**
     * The body a consumer receives is verifiable against the hash the outbox stored for it.
     *
     * <p>{@code payload_document} is {@code jsonb}, which normalises whitespace and key order, so the
     * producer's original bytes are already gone by the time anything reads the column — "verbatim"
     * can only mean "exactly as the column renders it". That is enough, and it is what the contract
     * actually needs: {@code prepare_outbox_envelope} computes {@code payload_hash} over the same
     * rendering, so a consumer can hash what it received and compare. This test pins that agreement,
     * because a relay that reserialised the JSON would break it silently.
     */
    @Test
    void publishesABodyThatMatchesTheStoredPayloadHash() {
        seed(1, ROUTED);

        relay.relayOnce();

        String storedRendering = jdbc.queryForObject(
                "select payload_document::text from operations.outbox_messages where id = ?",
                String.class, messageId(1));
        String storedHash = jdbc.queryForObject(
                "select payload_hash from operations.outbox_messages where id = ?",
                String.class, messageId(1));

        assertThat(publisher.published).singleElement().satisfies(message -> {
            assertThat(message.payloadDocument()).isEqualTo(storedRendering);
            assertThat(sha256(message.payloadDocument())).isEqualTo(storedHash);
            assertThat(message.eventType()).isEqualTo(ROUTED);
            assertThat(message.eventSchemaVersion()).isEqualTo("strategy-bot.v1");
            assertThat(message.idempotencyKey()).isEqualTo(idempotencyKey(1));
            assertThat(message.ownerDomain()).isEqualTo("strategy-bot");
            assertThat(message.aggregateId()).isEqualTo(BOT);
        });
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    @Test
    void retriesOnBackoffThenDeadLetters() {
        seed(1, ROUTED);
        publisher.failWith(new IllegalStateException("transport unavailable"));

        assertThat(relay.relayOnce()).isZero();
        assertThat(status(1)).isEqualTo("PENDING");
        assertThat(attempts(1)).isEqualTo(1);
        assertThat(failureCode(1)).isEqualTo("IllegalStateException");

        // Inside the backoff window the row is not due, so a cycle leaves it untouched.
        assertThat(relay.relayOnce()).isZero();
        assertThat(attempts(1)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(61));
        assertThat(relay.relayOnce()).isZero();
        assertThat(attempts(1)).isEqualTo(2);

        clock.advance(Duration.ofSeconds(61));
        assertThat(relay.relayOnce()).isZero();
        assertThat(status(1)).isEqualTo("DEAD_LETTERED");
        assertThat(attempts(1)).isEqualTo(3);
        assertThat(deadLetterReason(1)).isEqualTo("IllegalStateException");

        // A dead letter is terminal: no later cycle picks it up again.
        clock.advance(Duration.ofHours(1));
        assertThat(relay.relayOnce()).isZero();
        assertThat(attempts(1)).isEqualTo(3);
    }

    /** A relay that died mid-publish must not strand its rows once the lease lapses. */
    @Test
    void takesOverAClaimWhoseLeaseLapsed() {
        seed(1, ROUTED);
        jdbc.update("""
                update operations.outbox_messages set
                    delivery_status = 'CLAIMED', claim_token = gen_random_uuid(),
                    claimed_by = 'dead-replica', claimed_at = ?, claim_expires_at = ?
                where id = ?
                """,
                T0.atOffset(ZoneOffset.UTC),
                T0.plusSeconds(30).atOffset(ZoneOffset.UTC),
                messageId(1));

        clock.advance(Duration.ofSeconds(31));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(status(1)).isEqualTo("PUBLISHED");
        assertThat(publisher.published).hasSize(1);
    }

    // ------------------------------------------------------------------ seeding and reads

    private void seed(long sequence, String eventType) {
        jdbc.update("""
                insert into operations.outbox_messages (
                    id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                    event_schema_version, payload_document, idempotency_key, created_at)
                values (?, 'strategy-bot', ?, ?, ?, 'strategy-bot.v1', cast(? as jsonb), ?, ?)
                """,
                messageId(sequence), BOT, sequence, eventType, payload(sequence),
                idempotencyKey(sequence),
                clock.instant().atOffset(ZoneOffset.UTC));
    }

    private static UUID messageId(long sequence) {
        return UUID.nameUUIDFromBytes(
                ("relay-message:" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String idempotencyKey(long sequence) {
        return "sha256:" + "0".repeat(63) + sequence;
    }

    private static String payload(long sequence) {
        return "{\"botId\":\"" + BOT + "\",\"sequence\":" + sequence + "}";
    }

    private String status(long sequence) {
        return jdbc.queryForObject(
                "select delivery_status::text from operations.outbox_messages where id = ?",
                String.class, messageId(sequence));
    }

    private int attempts(long sequence) {
        return jdbc.queryForObject(
                "select publish_attempt_count from operations.outbox_messages where id = ?",
                Integer.class, messageId(sequence));
    }

    private String failureCode(long sequence) {
        return jdbc.queryForObject(
                "select last_failure_code from operations.outbox_messages where id = ?",
                String.class, messageId(sequence));
    }

    private String deadLetterReason(long sequence) {
        return jdbc.queryForObject(
                "select dead_letter_reason_code from operations.outbox_messages where id = ?",
                String.class, messageId(sequence));
    }

    private static final class RecordingPublisher implements OutboxMessagePublisher {
        private final List<PublishableOutboxMessage> published = new ArrayList<>();
        private RuntimeException failure;

        void failWith(RuntimeException value) {
            this.failure = value;
        }

        @Override
        public void publish(PublishableOutboxMessage message) {
            if (failure != null) {
                throw failure;
            }
            published.add(message);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
