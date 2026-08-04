package com.idea2strategy.backend.worker.outbox;

import com.idea2strategy.backend.application.outbox.OutboxMessagePublisher;
import com.idea2strategy.backend.application.outbox.PublishableOutboxMessage;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Publishes outbox messages onto the SQS queues COM03 fixed as the durable transport.
 *
 * <p>One queue per event type, from an explicit routing map. A single queue carrying two contracts
 * would force every consumer to parse messages meant for another — D's release intake says exactly
 * that about sharing its queue with D's own job messages — so an event type with no configured queue
 * is a configuration error rather than a message quietly sent somewhere plausible.
 *
 * <p>The body is the payload as the outbox rendered it, unmodified. The envelope travels as message
 * attributes so a queue can be triaged without parsing bodies, and so a consumer can reject a
 * contract version it does not implement before deserialising. Producer and delivery idempotency
 * remain separate: an operator replay is another delivery of the same immutable producer message,
 * not a new domain command.
 *
 * <p>{@code SendMessage} is synchronous and SQS acknowledges only durable writes, so returning from
 * {@link #publish} means the message is safely queued — which is what lets the relay record
 * {@code PUBLISHED} afterwards. Any failure propagates for the relay to turn into a retry or a dead
 * letter.
 */
public class SqsOutboxMessagePublisher implements OutboxMessagePublisher {

    private final SqsClient sqs;
    private final Map<String, String> queueUrlByEventType;

    public SqsOutboxMessagePublisher(SqsClient sqs, Map<String, String> queueUrlByEventType) {
        this.sqs = Objects.requireNonNull(sqs, "sqs");
        this.queueUrlByEventType = Map.copyOf(
                Objects.requireNonNull(queueUrlByEventType, "queueUrlByEventType"));
        if (this.queueUrlByEventType.isEmpty()) {
            throw new IllegalArgumentException("a publisher with no routes would publish nothing");
        }
    }

    /** The event types this publisher can route, which is what the relay should claim. */
    public java.util.Set<String> routedEventTypes() {
        return queueUrlByEventType.keySet();
    }

    @Override
    public void publish(PublishableOutboxMessage message) {
        Objects.requireNonNull(message, "message");
        String queueUrl = queueUrlByEventType.get(message.eventType());
        if (queueUrl == null) {
            throw new IllegalStateException(
                    "no queue is configured for event type " + message.eventType());
        }
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(message.payloadDocument())
                .messageAttributes(Map.of(
                        "eventType", attribute(message.eventType()),
                        "contractVersion", attribute(message.eventSchemaVersion()),
                        "ownerDomain", attribute(message.ownerDomain()),
                        "aggregateId", attribute(message.aggregateId().toString()),
                        "aggregateSequence", attribute(Long.toString(message.aggregateSequence())),
                        "messageId", attribute(message.messageId().toString()),
                        "idempotencyKey", attribute(message.producerIdempotencyKey()),
                        "outboxIdempotencyKey", attribute(message.deliveryIdempotencyKey()),
                        "payloadHash", attribute(message.payloadHash())))
                .build());
    }

    private static MessageAttributeValue attribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
