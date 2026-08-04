package com.idea2strategy.backend.persistence.outbox;

import com.idea2strategy.backend.application.outbox.OutboxMessagePublisher;
import com.idea2strategy.backend.application.outbox.PublishableOutboxMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Publishes the transactional outbox onto the transport its consumers listen on.
 *
 * <p>A17 gave the outbox a durable home and COM03 fixed the transport as SQS, but nothing ever moved
 * a row from one to the other: every producer's message sat at {@code PENDING} forever. D's release
 * intake says so in its own module note — the backend change it needs is the {@code backtest.runs}
 * deletion (root #138, merged) *and* "a relay publish it onto the release queue this module
 * consumes". This is that relay.
 *
 * <p>The relay owns {@code delivery_status}, because publishing is what that column describes.
 * Consumers keep their own receipts in {@code operations.outbox_consumer_receipts} and never touch
 * this state — one message can have many consumers, and none of them can speak for the others.
 *
 * <p>Delivery is at-least-once by construction: a crash after the transport accepted a message but
 * before the {@code PUBLISHED} write leaves the row {@code CLAIMED} until its lease lapses, and the
 * next cycle republishes it. That is why every consumer contract in this system carries an
 * idempotency key. Publishing inside the claim transaction instead would trade this duplicate for a
 * lost message, which is the worse failure.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Claims due rows of the routed event types.
     *
     * <p>{@code for update skip locked} lets several relay replicas share the backlog without
     * blocking each other, and the lease predicate is what lets a replica that died mid-publish be
     * taken over rather than stalling its rows.
     */
    private static final String CLAIM = """
            update operations.outbox_messages set
                delivery_status = 'CLAIMED',
                claim_token = gen_random_uuid(),
                claimed_by = :claimedBy,
                claimed_at = :now,
                claim_expires_at = :leaseExpiresAt,
                next_attempt_at = null
            where id in (
                select id from operations.outbox_messages
                where event_type = any(:eventTypes)
                  and (delivery_status = 'PENDING'
                       or (delivery_status = 'CLAIMED' and claim_expires_at <= :now))
                  and (next_attempt_at is null or next_attempt_at <= :now)
                order by created_at, aggregate_sequence, id
                limit :batchSize
                for update skip locked)
            returning id, claim_token, owner_domain, aggregate_id, aggregate_sequence, event_type,
                      event_schema_version, producer_idempotency_key, idempotency_key, payload_hash,
                      payload_document::text as payload_document, publish_attempt_count, created_at
            """;

    private static final String PUBLISHED = """
            update operations.outbox_messages set
                delivery_status = 'PUBLISHED',
                published_at = :now,
                claim_token = null, claimed_by = null, claimed_at = null, claim_expires_at = null,
                next_attempt_at = null,
                last_failure_code = null
            where id = :id and claim_token = :claimToken
            """;

    private static final String RETRY = """
            update operations.outbox_messages set
                delivery_status = 'PENDING',
                claim_token = null, claimed_by = null, claimed_at = null, claim_expires_at = null,
                publish_attempt_count = publish_attempt_count + 1,
                next_attempt_at = :nextAttemptAt,
                last_failure_code = :failureCode
            where id = :id and claim_token = :claimToken
            """;

    private static final String DEAD_LETTER = """
            update operations.outbox_messages set
                delivery_status = 'DEAD_LETTERED',
                dead_lettered_at = :now,
                dead_letter_reason_code = :failureCode,
                claim_token = null, claimed_by = null, claimed_at = null, claim_expires_at = null,
                publish_attempt_count = publish_attempt_count + 1,
                next_attempt_at = null,
                last_failure_code = :failureCode
            where id = :id and claim_token = :claimToken
            """;

    private final JdbcClient jdbc;
    private final OutboxMessagePublisher publisher;
    private final Clock clock;
    private final String relayId;
    private final Set<String> routedEventTypes;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryBackoff;
    private final int maxAttempts;

    public OutboxRelay(
            JdbcClient jdbc,
            OutboxMessagePublisher publisher,
            Clock clock,
            String relayId,
            Set<String> routedEventTypes,
            int batchSize,
            Duration lease,
            Duration retryBackoff,
            int maxAttempts) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.relayId = requireText(relayId, "relayId");
        this.routedEventTypes = Set.copyOf(Objects.requireNonNull(routedEventTypes, "routedEventTypes"));
        if (this.routedEventTypes.isEmpty()) {
            throw new IllegalArgumentException("a relay with no routed event types would publish nothing");
        }
        this.batchSize = requirePositive(batchSize, "batchSize");
        this.lease = requirePositiveDuration(lease, "lease");
        this.retryBackoff = requirePositiveDuration(retryBackoff, "retryBackoff");
        this.maxAttempts = requirePositive(maxAttempts, "maxAttempts");
    }

    /** One relay cycle. Returns how many messages the transport accepted. */
    public int relayOnce() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<ClaimedMessage> claimed = jdbc.sql(CLAIM)
                .param("claimedBy", relayId)
                .param("now", now)
                .param("leaseExpiresAt", now.plus(lease))
                .param("eventTypes", routedEventTypes.toArray(String[]::new))
                .param("batchSize", batchSize)
                .query((rs, row) -> new ClaimedMessage(
                        rs.getObject("claim_token", UUID.class),
                        rs.getInt("publish_attempt_count"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        new PublishableOutboxMessage(
                                rs.getObject("id", UUID.class),
                                rs.getString("owner_domain"),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getLong("aggregate_sequence"),
                                rs.getString("event_type"),
                                rs.getString("event_schema_version"),
                                rs.getString("producer_idempotency_key"),
                                rs.getString("idempotency_key"),
                                rs.getString("payload_hash"),
                                rs.getString("payload_document"))))
                .list();

        // `UPDATE ... RETURNING` reports rows in the order the update touched them rather than the
        // claim subquery's order, so producer order is re-established here. A consumer that reasons
        // about a bot's command sequence needs the earlier message first.
        List<ClaimedMessage> inProducerOrder = claimed.stream()
                .sorted(java.util.Comparator.comparing(ClaimedMessage::createdAt)
                        .thenComparing(claim -> claim.message().aggregateSequence())
                        .thenComparing(claim -> claim.message().messageId()))
                .toList();

        int published = 0;
        for (ClaimedMessage claim : inProducerOrder) {
            try {
                publisher.publish(claim.message());
                jdbc.sql(PUBLISHED)
                        .param("now", now)
                        .param("id", claim.message().messageId())
                        .param("claimToken", claim.claimToken())
                        .update();
                published++;
            } catch (RuntimeException failure) {
                recordFailure(claim, now, failure);
            }
        }
        return published;
    }

    private void recordFailure(ClaimedMessage claim, OffsetDateTime now, RuntimeException failure) {
        UUID messageId = claim.message().messageId();
        int attempts = claim.publishAttemptCount() + 1;
        String failureCode = boundedCode(failure);
        if (attempts >= maxAttempts) {
            log.error("outbox message {} dead-lettered after {} publish attempts", messageId, attempts, failure);
            jdbc.sql(DEAD_LETTER)
                    .param("now", now)
                    .param("failureCode", failureCode)
                    .param("id", messageId)
                    .param("claimToken", claim.claimToken())
                    .update();
            return;
        }
        log.warn("outbox message {} publish attempt {} failed; retrying after {}",
                messageId, attempts, retryBackoff, failure);
        jdbc.sql(RETRY)
                .param("nextAttemptAt", now.plus(retryBackoff))
                .param("failureCode", failureCode)
                .param("id", messageId)
                .param("claimToken", claim.claimToken())
                .update();
    }

    private static String boundedCode(RuntimeException failure) {
        String code = failure.getClass().getSimpleName();
        return code.substring(0, Math.min(code.length(), 80));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record ClaimedMessage(
            UUID claimToken,
            int publishAttemptCount,
            OffsetDateTime createdAt,
            PublishableOutboxMessage message) {}
}
