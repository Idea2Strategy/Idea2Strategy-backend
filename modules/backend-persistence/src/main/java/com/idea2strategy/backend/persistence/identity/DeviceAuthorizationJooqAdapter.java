package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.DeviceAuthorizationCommandPort;
import com.idea2strategy.backend.application.identity.DeviceAuthorizationOutcome;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device authorization requests.
 *
 * <p>Approval and consumption are single conditional statements rather than read-then-write. Two
 * polls arriving together must not both collect a token, and an approval racing an expiry must not
 * revive a dead request; letting the database decide is what makes that true without a lock.
 */
@Component
public class DeviceAuthorizationJooqAdapter implements DeviceAuthorizationCommandPort {
    private final DSLContext dsl;

    public DeviceAuthorizationJooqAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public UUID create(
            String deviceCodeDigest,
            String userCodeDigest,
            short digestKeyVersion,
            String clientLabel,
            short pollIntervalSeconds,
            Instant requestedAt,
            Instant expiresAt) {
        UUID id = UUID.randomUUID();
        dsl.execute(
                "insert into identity.device_authorization_requests ("
                        + "id, device_code_digest, user_code_digest, digest_key_version, client_label, "
                        + "status, poll_interval_seconds, requested_at, expires_at) "
                        + "values (?, ?, ?, ?, ?, 'PENDING', ?, ?::timestamptz, ?::timestamptz)",
                id, deviceCodeDigest, userCodeDigest, digestKeyVersion, clientLabel,
                pollIntervalSeconds, offset(requestedAt), offset(expiresAt));
        return id;
    }

    @Override
    public boolean approve(String userCodeDigest, UUID accountId, Instant at) {
        return dsl.execute(
                "update identity.device_authorization_requests set status = 'APPROVED', "
                        + "approved_account_id = ?, approved_at = ?::timestamptz "
                        + "where user_code_digest = ? and status = 'PENDING' "
                        + "and expires_at > ?::timestamptz",
                accountId, offset(at), userCodeDigest, offset(at)) == 1;
    }

    @Override
    public boolean deny(String userCodeDigest, Instant at) {
        return dsl.execute(
                "update identity.device_authorization_requests set status = 'DENIED', "
                        + "denied_at = ?::timestamptz "
                        + "where user_code_digest = ? and status = 'PENDING' "
                        + "and expires_at > ?::timestamptz",
                offset(at), userCodeDigest, offset(at)) == 1;
    }

    @Override
    @Transactional
    public DeviceAuthorizationOutcome consume(String deviceCodeDigest, Instant at) {
        // The update is the decision: only an approved, unexpired request can move to CONSUMED, and
        // only one statement can win it. Reading the row first and updating after would let two
        // polls both see APPROVED.
        Record consumed = dsl.fetchOne(
                "update identity.device_authorization_requests set status = 'CONSUMED', "
                        + "consumed_at = ?::timestamptz, last_polled_at = ?::timestamptz "
                        + "where device_code_digest = ? and status = 'APPROVED' "
                        + "and expires_at > ?::timestamptz "
                        + "returning approved_account_id",
                offset(at), offset(at), deviceCodeDigest, offset(at));
        if (consumed != null) {
            return DeviceAuthorizationOutcome.approved(consumed.get("approved_account_id", UUID.class));
        }

        Record current = dsl.fetchOne(
                "select status::text as status, expires_at from identity.device_authorization_requests "
                        + "where device_code_digest = ?",
                deviceCodeDigest);
        if (current == null) {
            return DeviceAuthorizationOutcome.of(DeviceAuthorizationOutcome.Status.UNKNOWN);
        }
        dsl.execute(
                "update identity.device_authorization_requests set last_polled_at = ?::timestamptz "
                        + "where device_code_digest = ?",
                offset(at), deviceCodeDigest);

        var expiresAt = current.get("expires_at", java.time.OffsetDateTime.class);
        if (expiresAt != null && !expiresAt.toInstant().isAfter(at)) {
            return DeviceAuthorizationOutcome.of(DeviceAuthorizationOutcome.Status.EXPIRED);
        }
        return switch (current.get("status", String.class)) {
            case "PENDING" -> DeviceAuthorizationOutcome.pending();
            case "DENIED" -> DeviceAuthorizationOutcome.of(DeviceAuthorizationOutcome.Status.DENIED);
            // Already collected. Reporting UNKNOWN rather than APPROVED keeps a replayed device
            // code from looking like a fresh grant.
            default -> DeviceAuthorizationOutcome.of(DeviceAuthorizationOutcome.Status.UNKNOWN);
        };
    }

    private static java.time.OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
