package com.idea2strategy.backend.worker.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.outbox.PublishableOutboxMessage;
import com.idea2strategy.backend.persistence.competition.RoomEvaluationAccountResultConsumer;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ClaimedMessage;
import com.idea2strategy.backend.worker.outbox.SqsOutboxMessagePublisher;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Testcontainers(disabledWithoutDocker = true)
class RoomEvaluationResultRelayLocalStackTest {

    private static final String OPENED = "ROOM_EVALUATION_ACCOUNT_OPENED";
    private static final String REJECTED = "ROOM_EVALUATION_ACCOUNT_OPEN_REJECTED";

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:3.8").withServices("sqs");

    @Test
    void tradingResultRoutesPublishToTheQueuesPolledByTheBackendConsumer() {
        try (SqsClient sqs = client()) {
            String openedQueue = sqs.createQueue(request -> request.queueName("room-ledger-opened-e2e"))
                    .queueUrl();
            String rejectedQueue = sqs.createQueue(request -> request.queueName("room-ledger-rejected-e2e"))
                    .queueUrl();
            var publisher = new SqsOutboxMessagePublisher(sqs, Map.of(
                    OPENED, openedQueue,
                    REJECTED, rejectedQueue));
            var consumer = mock(RoomEvaluationAccountResultConsumer.class);
            when(consumer.consume(any(ClaimedMessage.class), anyString(), any(Duration.class)))
                    .thenReturn(RoomEvaluationAccountResultConsumer.Outcome.OPENED);
            var properties = new RoomEvaluationAccountResultSqsConfiguration.Properties(
                    true, openedQueue, rejectedQueue, null, null, null, null,
                    "room-ledger-e2e", 10, 5, Duration.ofSeconds(30));
            var worker = new RoomEvaluationAccountResultSqsConfiguration.Worker(sqs, consumer, properties);

            publisher.publish(message(OPENED, 1));
            publisher.publish(message(REJECTED, 2));
            worker.poll();

            var messages = ArgumentCaptor.forClass(ClaimedMessage.class);
            verify(consumer, org.mockito.Mockito.times(2))
                    .consume(messages.capture(), anyString(), any(Duration.class));
            assertThat(messages.getAllValues())
                    .extracting(ClaimedMessage::eventType)
                    .containsExactlyInAnyOrder(OPENED, REJECTED);
            assertThat(publisher.routedEventTypes()).containsExactlyInAnyOrder(OPENED, REJECTED);
        }
    }

    @Test
    void longPollingDefaultsToFiveSecondsAndStaysWithinTheSqsRange() {
        assertThat(properties(null).longPollSeconds()).isEqualTo(5);
        assertThat(properties(0).longPollSeconds()).isEqualTo(1);
        assertThat(properties(21).longPollSeconds()).isEqualTo(20);
    }

    private static RoomEvaluationAccountResultSqsConfiguration.Properties properties(Integer seconds) {
        return new RoomEvaluationAccountResultSqsConfiguration.Properties(
                true, "opened", "rejected", null, null, null, null,
                null, null, seconds, null);
    }

    private static PublishableOutboxMessage message(String eventType, long sequence) {
        UUID id = UUID.randomUUID();
        return new PublishableOutboxMessage(
                id,
                "trading",
                UUID.randomUUID(),
                sequence,
                eventType,
                "competition-room-evaluation-account.v1",
                "producer:" + id,
                "delivery:" + id,
                "sha256:" + "7".repeat(64),
                "{\"eventType\":\"" + eventType + "\"}");
    }

    private static SqsClient client() {
        return SqsClient.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }
}
