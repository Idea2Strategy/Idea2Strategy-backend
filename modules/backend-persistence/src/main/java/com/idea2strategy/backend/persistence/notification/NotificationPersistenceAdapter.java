package com.idea2strategy.backend.persistence.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.notification.NotificationChannel;
import com.idea2strategy.backend.application.notification.EmailNotificationPreferencePort;
import com.idea2strategy.backend.application.notification.NotificationPolicy;
import com.idea2strategy.backend.application.notification.NotificationPolicyPort;
import com.idea2strategy.backend.application.notification.NotificationQueryPort;
import com.idea2strategy.backend.application.notification.NotificationRequest;
import com.idea2strategy.backend.application.notification.NotificationStore;
import com.idea2strategy.backend.application.notification.NotificationUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationPersistenceAdapter
        implements NotificationPolicyPort, EmailNotificationPreferencePort, NotificationStore,
        NotificationQueryPort {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public NotificationPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public NotificationPolicy requireActive(String typeCode) {
        List<NotificationPolicy> rows = jdbc.query("""
                select type_code, policy_version, mandatory, default_channels::text
                from operations.notification_policies where type_code = ? and active
                """, (rs, row) -> new NotificationPolicy(
                rs.getString(1), rs.getString(2), rs.getBoolean(3), channels(rs.getString(4))), typeCode);
        if (rows.size() != 1) throw new NotificationUnavailableException();
        return rows.getFirst();
    }

    @Override
    public boolean enabled(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select coalesce((
                    select enabled from operations.account_email_notification_preferences
                    where account_id = ?
                ), false)
                """, Boolean.class, accountId));
    }

    @Override
    public void replace(UUID accountId, boolean enabled, Instant updatedAt) {
        jdbc.update("""
                insert into operations.account_email_notification_preferences
                    (account_id, enabled, updated_at)
                values (?, ?, ?)
                on conflict (account_id) do update
                set enabled = excluded.enabled, updated_at = excluded.updated_at
                """, accountId, enabled, Timestamp.from(updatedAt));
    }

    @Override
    @Transactional
    public NotificationReceipt create(
            NotificationRequest request, NotificationPolicy policy,
            Set<NotificationChannel> channels, Instant now) {
        UUID id = UUID.randomUUID();
        String channelJson = write(channels.stream().map(Enum::name).sorted().toList());
        String argumentsJson = write(request.templateArguments());
        String key = "notification:" + sha256(request.accountId() + "|" + request.typeCode()
                + "|" + request.sourceEventId());
        int inserted = jdbc.update("""
                insert into operations.notifications
                    (id, account_id, notification_type, mandatory, locale, template_version,
                     payload_document, idempotency_key, created_at, source_event_id,
                     source_event_hash, policy_version, selected_channels, correlation_id)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                on conflict (account_id, notification_type, source_event_id)
                    where source_event_id is not null do nothing
                """, id, request.accountId(), request.typeCode(), policy.mandatory(), request.locale(),
                request.templateVersion(), argumentsJson, key, Timestamp.from(now), request.sourceEventId(),
                request.sourceEventHash(), policy.policyVersion(), channelJson, request.correlationId());
        if (inserted == 0) return replay(request);

        if (channels.contains(NotificationChannel.EMAIL)) {
            UUID messageId = UUID.randomUUID();
            String payload = write(Map.of("notificationId", id.toString()));
            jdbc.update("""
                    insert into operations.outbox_messages
                        (id, owner_domain, aggregate_id, event_type, event_schema_version,
                         payload_document, idempotency_key, created_at)
                    values (?, 'notification', ?, 'NOTIFICATION_EMAIL_DELIVERY', '1',
                            cast(? as jsonb), ?, ?)
                    """, messageId, id, payload, "notification-email:" + id, Timestamp.from(now));
        }
        return new NotificationReceipt(id, channels, false);
    }

    private NotificationReceipt replay(NotificationRequest request) {
        return jdbc.queryForObject("""
                select id, source_event_hash, template_version, locale, selected_channels::text
                from operations.notifications
                where account_id = ? and notification_type = ? and source_event_id = ?
                """, (rs, row) -> {
            if (!request.sourceEventHash().equals(rs.getString(2))
                    || !request.templateVersion().equals(rs.getString(3))
                    || !request.locale().equals(rs.getString(4))) {
                throw new NotificationEvidenceConflictException();
            }
            return new NotificationReceipt(rs.getObject(1, UUID.class), channels(rs.getString(5)), true);
        }, request.accountId(), request.typeCode(), request.sourceEventId());
    }

    @Override
    @Transactional
    public boolean markRead(UUID accountId, UUID notificationId, Instant now) {
        return jdbc.update("""
                update operations.notifications set read_at = coalesce(read_at, ?)
                where id = ? and account_id = ?
                """, Timestamp.from(now), notificationId, accountId) == 1;
    }

    @Override
    public NotificationPage listOwned(UUID accountId, Instant beforeCreatedAt, UUID beforeId, int limit) {
        String cursor = beforeCreatedAt == null ? "" : "and (created_at, id) < (?, ?)";
        Object[] args = beforeCreatedAt == null
                ? new Object[] {accountId, limit + 1}
                : new Object[] {accountId, Timestamp.from(beforeCreatedAt), beforeId, limit + 1};
        List<NotificationItem> rows = jdbc.query("""
                select id, notification_type, mandatory, template_version, locale,
                       payload_document::text, created_at, read_at
                from operations.notifications
                where account_id = ? %s
                order by created_at desc, id desc limit ?
                """.formatted(cursor), (rs, row) -> new NotificationItem(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                rs.getString(5), readMap(rs.getString(6)), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()), args);
        boolean more = rows.size() > limit;
        List<NotificationItem> page = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        NotificationItem last = more ? page.getLast() : null;
        return new NotificationPage(page, last == null ? null : last.createdAt(), last == null ? null : last.id());
    }

    private Set<NotificationChannel> channels(String value) {
        try {
            var result = EnumSet.noneOf(NotificationChannel.class);
            for (String channel : json.readValue(value, STRING_LIST)) result.add(NotificationChannel.valueOf(channel));
            return Set.copyOf(result);
        } catch (Exception e) {
            throw new IllegalStateException("invalid notification channels", e);
        }
    }

    private Map<String, String> readMap(String value) {
        try { return json.readValue(value, STRING_MAP); }
        catch (Exception e) { throw new IllegalStateException("invalid notification snapshot", e); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("notification JSON serialization failed", e); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static final class NotificationEvidenceConflictException extends RuntimeException {}
}
