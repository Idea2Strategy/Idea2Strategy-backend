package com.idea2strategy.backend.application.outbox;

/**
 * Publishes one claimed outbox message onto the transport its consumers listen on.
 *
 * <p>Kept a port because the relay's job — claim, publish, record the outcome — is the same whatever
 * the transport is, and because the claim protocol has to be testable against a real database without
 * standing up a broker.
 *
 * <p>An implementation must be synchronous: the relay records {@code PUBLISHED} only after this
 * returns, so returning before the message is durably accepted would let the relay mark a message
 * delivered that no consumer will ever see. Throwing is the correct response to any failure — the
 * relay decides whether that becomes a retry or a dead letter.
 */
public interface OutboxMessagePublisher {

    void publish(PublishableOutboxMessage message);
}
