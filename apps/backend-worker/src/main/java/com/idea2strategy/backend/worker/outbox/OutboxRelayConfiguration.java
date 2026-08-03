package com.idea2strategy.backend.worker.outbox;

import com.idea2strategy.backend.persistence.outbox.OutboxRelay;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Wires the outbox relay when a deployment turns it on.
 *
 * <p>{@code idea2strategy.outbox-relay.enabled} is an explicit opt-in rather than a default, because
 * a relay is only correct once someone has decided which queue each contract goes to. A deployment
 * that has not been given queues yet starts and runs its other duties instead of failing to wire.
 *
 * <p>The flag is deliberately not inferred from the {@code queues} map being non-empty: a
 * {@code @ConditionalOnProperty} on a map property never matches, because the real property keys are
 * {@code queues.<EVENT_TYPE>} rather than {@code queues}. Enabling without routes fails fast in
 * {@link SqsOutboxMessagePublisher} instead of starting a relay that would publish nothing.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayConfiguration.RelayProperties.class)
public class OutboxRelayConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "idea2strategy.outbox-relay", name = "enabled", havingValue = "true")
    SqsClient outboxRelaySqsClient(RelayProperties properties) {
        var builder = SqsClient.builder();
        // Anything left unset falls through to the SDK's own chains, which is what an AWS deployment
        // wants: the region comes from the environment and the credentials from the instance role.
        // A local deployment points every one of them at LocalStack instead, and the SDK's chains
        // never read Spring properties, so they have to be applied here explicitly.
        if (hasText(properties.endpointOverride())) {
            builder.endpointOverride(URI.create(properties.endpointOverride()));
        }
        if (hasText(properties.region())) {
            builder.region(Region.of(properties.region()));
        }
        if (hasText(properties.accessKeyId()) && hasText(properties.secretAccessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())));
        }
        return builder.build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Bean
    @ConditionalOnProperty(prefix = "idea2strategy.outbox-relay", name = "enabled", havingValue = "true")
    SqsOutboxMessagePublisher outboxMessagePublisher(SqsClient sqs, RelayProperties properties) {
        return new SqsOutboxMessagePublisher(sqs, new LinkedHashMap<>(properties.queues()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "idea2strategy.outbox-relay", name = "enabled", havingValue = "true")
    OutboxRelayWorker outboxRelayWorker(
            JdbcClient jdbc, SqsOutboxMessagePublisher publisher, RelayProperties properties) {
        return new OutboxRelayWorker(new OutboxRelay(
                jdbc,
                publisher,
                Clock.systemUTC(),
                properties.relayId(),
                publisher.routedEventTypes(),
                properties.batchSize(),
                properties.lease(),
                properties.retryBackoff(),
                properties.maxAttempts()));
    }

    /** The schedule around one {@link OutboxRelay#relayOnce()} cycle. */
    public static final class OutboxRelayWorker {
        private final OutboxRelay relay;

        OutboxRelayWorker(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${idea2strategy.outbox-relay.poll-delay:PT1S}")
        public void relay() {
            relay.relayOnce();
        }
    }

    /**
     * @param queues event type to queue URL. One queue per contract, never a shared one.
     */
    @ConfigurationProperties(prefix = "idea2strategy.outbox-relay")
    public record RelayProperties(
            Map<String, String> queues,
            String endpointOverride,
            String region,
            String accessKeyId,
            String secretAccessKey,
            String relayId,
            Integer batchSize,
            Duration lease,
            Duration retryBackoff,
            Integer maxAttempts) {

        public RelayProperties {
            queues = queues == null ? Map.of() : Map.copyOf(queues);
            relayId = relayId == null || relayId.isBlank() ? "backend-worker" : relayId;
            batchSize = batchSize == null ? 32 : batchSize;
            lease = lease == null ? Duration.ofSeconds(30) : lease;
            retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
            maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        }
    }
}
