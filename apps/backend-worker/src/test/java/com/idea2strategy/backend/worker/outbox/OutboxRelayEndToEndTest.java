package com.idea2strategy.backend.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * D91's transport, end to end: a row B wrote to the outbox arrives on the queue D's release intake
 * consumes.
 *
 * <p>This is the half root #138 left open. B's release path writes {@code OFFICIAL_BACKTEST_REQUESTED}
 * to {@code operations.outbox_messages} and stops there — that PR removed the illegal
 * {@code backtest.runs} insert precisely so the run row would be created by its owner instead. D's
 * {@code OfficialBacktestIntake} consumes the message from SQS and creates the run. Nothing carried
 * the message between the two, so both halves passed their own tests while the path did not exist.
 *
 * <p>Everything here is real: a PostgreSQL container with the canonical schema, a LocalStack SQS
 * queue, the wired worker, and the scheduled relay's own cycle. The assertion is that the body on the
 * queue is the one the outbox stored, hashing to the {@code payload_hash} a consumer verifies against,
 * and that the row is marked PUBLISHED exactly once.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "idea2strategy.outbox-relay.enabled=true",
        "idea2strategy.outbox-relay.queues.OFFICIAL_BACKTEST_REQUESTED=${test.queue.url}",
        "idea2strategy.outbox-relay.endpoint-override=${test.sqs.endpoint}",
        "idea2strategy.outbox-relay.poll-delay=PT600S",
        "idea2strategy.outbox-relay.initial-delay=PT600S",
        "spring.flyway.enabled=true",
        "idea2strategy.outbox-relay.region=${test.sqs.region}",
        "idea2strategy.outbox-relay.access-key-id=${test.sqs.access-key}",
        "idea2strategy.outbox-relay.secret-access-key=${test.sqs.secret-key}"
})
class OutboxRelayEndToEndTest {

    private static final String ROUTED = "OFFICIAL_BACKTEST_REQUESTED";
    private static final UUID BOT = UUID.fromString("44000000-0000-4000-8000-0000000000d1");
    private static final UUID MESSAGE = UUID.fromString("44000000-0000-4000-8000-0000000000d2");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:3.8").withServices("sqs");

    private static SqsClient sqs;
    private static String queueUrl;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        sqs = SqsClient.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName("official-backtest-requests-e2e").build())
                .queueUrl();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("test.queue.url", () -> queueUrl);
        registry.add("test.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("test.sqs.region", LOCALSTACK::getRegion);
        registry.add("test.sqs.access-key", LOCALSTACK::getAccessKey);
        registry.add("test.sqs.secret-key", LOCALSTACK::getSecretKey);
    }

    @Autowired
    private OutboxRelayConfiguration.OutboxRelayWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anOutboxRowReachesTheQueueDConsumes() {
        String body = "{\"botId\": \"" + BOT + "\", \"requestReason\": \"STRATEGY_RELEASE\"}";
        jdbc.update("""
                insert into operations.outbox_messages (
                    id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                    event_schema_version, payload_document, idempotency_key, created_at)
                values (?, 'strategy-bot', ?, 1, ?, 'strategy-bot.v1', cast(? as jsonb), ?, ?)
                """,
                MESSAGE, BOT, ROUTED, body, "sha256:" + "7".repeat(64),
                OffsetDateTime.now(ZoneOffset.UTC));

        worker.relay();

        String storedRendering = jdbc.queryForObject(
                "select payload_document::text from operations.outbox_messages where id = ?",
                String.class, MESSAGE);
        String storedHash = jdbc.queryForObject(
                "select payload_hash from operations.outbox_messages where id = ?",
                String.class, MESSAGE);

        List<Message> received = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .messageAttributeNames("All")
                        .waitTimeSeconds(5)
                        .build())
                .messages();

        assertThat(received).singleElement().satisfies(message -> {
            assertThat(message.body()).isEqualTo(storedRendering);
            assertThat(sha256(message.body())).isEqualTo(storedHash);
            assertThat(message.messageAttributes().get("contractVersion").stringValue())
                    .isEqualTo("strategy-bot.v1");
            assertThat(message.messageAttributes().get("idempotencyKey").stringValue())
                    .isEqualTo("sha256:" + "7".repeat(64));
        });
        assertThat(jdbc.queryForObject(
                "select delivery_status::text from operations.outbox_messages where id = ?",
                String.class, MESSAGE)).isEqualTo("PUBLISHED");

        // A second cycle publishes nothing: PUBLISHED is terminal for the relay.
        worker.relay();
        assertThat(sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl).waitTimeSeconds(1).build())
                .messages()).isEmpty();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }
}
