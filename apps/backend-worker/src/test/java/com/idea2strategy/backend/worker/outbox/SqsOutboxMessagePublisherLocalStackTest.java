package com.idea2strategy.backend.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.outbox.PublishableOutboxMessage;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * The SQS publisher against a real queue.
 *
 * <p>The relay's state machine is proven separately against PostgreSQL with a recording publisher;
 * what is left to prove is that this side genuinely puts a message on a queue a consumer can read —
 * body unmodified, envelope on the attributes D's intake triages by — and that an unrouted contract
 * fails loudly instead of going somewhere plausible.
 */
@Testcontainers(disabledWithoutDocker = true)
class SqsOutboxMessagePublisherLocalStackTest {

    private static final String ROUTED = "OFFICIAL_BACKTEST_REQUESTED";
    private static final UUID BOT = UUID.fromString("22000000-0000-4000-8000-0000000000b1");

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:3.8").withServices("sqs");

    private static SqsClient sqs;
    private static String queueUrl;

    @BeforeAll
    static void createQueue() {
        sqs = SqsClient.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName("official-backtest-requests")
                .build())
                .queueUrl();
    }

    @AfterAll
    static void closeClient() {
        if (sqs != null) {
            sqs.close();
        }
    }

    @Test
    void putsTheBodyAndEnvelopeOnTheRoutedQueue() {
        var publisher = new SqsOutboxMessagePublisher(sqs, Map.of(ROUTED, queueUrl));
        String body = "{\"botId\": \"" + BOT + "\", \"requestReason\": \"STRATEGY_RELEASE\"}";
        UUID messageId = UUID.fromString("33000000-0000-4000-8000-0000000000c1");

        publisher.publish(new PublishableOutboxMessage(
                messageId, "strategy-bot", BOT, 1, ROUTED, "strategy-bot.v1",
                "sha256:" + "1".repeat(64), body));

        List<Message> received = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .messageAttributeNames("All")
                        .waitTimeSeconds(5)
                        .build())
                .messages();

        assertThat(received).singleElement().satisfies(message -> {
            assertThat(message.body()).isEqualTo(body);
            assertThat(message.messageAttributes().get("eventType").stringValue()).isEqualTo(ROUTED);
            assertThat(message.messageAttributes().get("contractVersion").stringValue())
                    .isEqualTo("strategy-bot.v1");
            assertThat(message.messageAttributes().get("ownerDomain").stringValue())
                    .isEqualTo("strategy-bot");
            assertThat(message.messageAttributes().get("aggregateId").stringValue())
                    .isEqualTo(BOT.toString());
            assertThat(message.messageAttributes().get("messageId").stringValue())
                    .isEqualTo(messageId.toString());
            assertThat(message.messageAttributes().get("idempotencyKey").stringValue())
                    .isEqualTo("sha256:" + "1".repeat(64));
        });
    }

    /** A contract with no queue is a configuration error, not a message sent somewhere plausible. */
    @Test
    void refusesAnUnroutedEventType() {
        var publisher = new SqsOutboxMessagePublisher(sqs, Map.of(ROUTED, queueUrl));

        assertThatThrownBy(() -> publisher.publish(new PublishableOutboxMessage(
                UUID.fromString("33000000-0000-4000-8000-0000000000c2"), "strategy-bot", BOT, 2,
                "BOT_RUN_COMMAND", "strategy-bot.v1", "sha256:" + "2".repeat(64), "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no queue is configured for event type BOT_RUN_COMMAND");
    }
}
