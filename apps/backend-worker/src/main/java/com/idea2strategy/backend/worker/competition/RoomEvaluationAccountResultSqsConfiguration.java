package com.idea2strategy.backend.worker.competition;

import com.idea2strategy.backend.persistence.competition.RoomEvaluationAccountResultConsumer;
import com.idea2strategy.backend.persistence.competition.RoomEvaluationAccountResultConsumer.Outcome;
import com.idea2strategy.backend.persistence.competition.RoomEvaluationStartJooqAdapter;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ClaimedMessage;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** Durable SQS intake for F's OPENED/REJECTED room-ledger facts. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RoomEvaluationAccountResultSqsConfiguration.Properties.class)
@Import({RoomEvaluationAccountResultConsumer.class, RoomEvaluationStartJooqAdapter.class,
        TransactionalOutboxStore.class})
public class RoomEvaluationAccountResultSqsConfiguration {

    @Bean("roomLedgerResultSqsClient")
    @ConditionalOnProperty(prefix = "idea2strategy.room-ledger-results", name = "enabled", havingValue = "true")
    SqsClient roomLedgerResultSqsClient(Properties properties) {
        var builder = SqsClient.builder();
        if (text(properties.endpointOverride())) builder.endpointOverride(URI.create(properties.endpointOverride()));
        if (text(properties.region())) builder.region(Region.of(properties.region()));
        if (text(properties.accessKeyId()) && text(properties.secretAccessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "idea2strategy.room-ledger-results", name = "enabled", havingValue = "true")
    Worker roomLedgerResultWorker(
            @Qualifier("roomLedgerResultSqsClient") SqsClient sqs,
            RoomEvaluationAccountResultConsumer consumer,
            Properties properties) {
        if (!text(properties.openedQueueUrl()) || !text(properties.rejectedQueueUrl())) {
            throw new IllegalStateException("both room ledger result queue URLs are required");
        }
        return new Worker(sqs, consumer, properties);
    }

    public static final class Worker {
        private final SqsClient sqs;
        private final RoomEvaluationAccountResultConsumer consumer;
        private final Properties properties;

        Worker(SqsClient sqs, RoomEvaluationAccountResultConsumer consumer, Properties properties) {
            this.sqs = sqs;
            this.consumer = consumer;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${idea2strategy.room-ledger-results.poll-delay:PT1S}")
        public void poll() {
            pollQueue(properties.openedQueueUrl());
            pollQueue(properties.rejectedQueueUrl());
            consumer.reconcilePending(properties.workerId(), properties.lease(), properties.batchSize());
        }

        private void pollQueue(String queueUrl) {
            List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                            .maxNumberOfMessages(properties.batchSize())
                            .waitTimeSeconds(properties.longPollSeconds())
                            .messageAttributeNames("All"))
                    .messages();
            for (Message message : messages) {
                try {
                    Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue> attributes =
                            message.messageAttributes();
                    Instant now = Instant.now();
                    var source = new ClaimedMessage(
                            java.util.UUID.fromString(attribute(attributes, "messageId")),
                            attribute(attributes, "ownerDomain"),
                            java.util.UUID.fromString(attribute(attributes, "aggregateId")),
                            attribute(attributes, "eventType"), attribute(attributes, "contractVersion"),
                            message.body(), attribute(attributes, "payloadHash"),
                            attribute(attributes, "idempotencyKey"), null, 0, now, now.plus(properties.lease()));
                    Outcome outcome = consumer.consume(source, properties.workerId(), properties.lease());
                    if (outcome != Outcome.IN_PROGRESS) {
                        sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                    }
                } catch (RuntimeException malformedOrTransient) {
                    // Leave the message for retry and the queue's configured DLQ redrive policy.
                }
            }
        }
    }

    private static String attribute(
            Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue> attributes, String name) {
        var value = attributes.get(name);
        if (value == null || !text(value.stringValue())) throw new IllegalArgumentException("missing " + name);
        return value.stringValue();
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }

    @ConfigurationProperties(prefix = "idea2strategy.room-ledger-results")
    public record Properties(
            boolean enabled, String openedQueueUrl, String rejectedQueueUrl,
            String endpointOverride, String region, String accessKeyId, String secretAccessKey,
            String workerId, Integer batchSize, Integer longPollSeconds, Duration lease) {
        public Properties {
            workerId = text(workerId) ? workerId : "backend-worker-room-ledger";
            batchSize = batchSize == null ? 10 : Math.max(1, Math.min(10, batchSize));
            longPollSeconds = longPollSeconds == null
                    ? 5 : Math.max(1, Math.min(20, longPollSeconds));
            lease = lease == null ? Duration.ofSeconds(30) : lease;
        }
    }
}
