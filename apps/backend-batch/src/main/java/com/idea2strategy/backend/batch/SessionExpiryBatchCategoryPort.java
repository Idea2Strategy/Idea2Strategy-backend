package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.identity.SessionExpiryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class SessionExpiryBatchCategoryPort implements BatchCategoryPort {
    private final SessionExpiryPort sessions;
    private final JdbcTemplate jdbc;

    public SessionExpiryBatchCategoryPort(SessionExpiryPort sessions, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.jdbc = jdbc;
    }

    @Override public BatchCategory category() { return BatchCategory.SESSION; }

    @Override
    public ClaimPage claimDue(ClaimRequest request) {
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        List<WorkItem> items = sessions.findDueSessions(request.limit()).stream()
                .map(identity -> new WorkItem(
                        category(), encode(identity), identity.expiresAt(),
                        "session-expiry:" + identity.sessionId() + ":" + identity.expiresAt(),
                        UUID.randomUUID(), 1))
                .toList();
        Cursor next = items.isEmpty() ? null : new Cursor(items.getLast().dueAt(), items.getLast().itemId());
        return new ClaimPage(databaseNow, items, next);
    }

    @Override
    public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
        try {
            return sessions.expire(decode(item.itemId()), correlationId) == SessionExpiryPort.Result.APPLIED
                    ? ItemResult.completed()
                    : ItemResult.alreadyCompleted();
        } catch (IllegalArgumentException exception) {
            return ItemResult.permanent("SESSION_EXPIRY_BATCH_ITEM_INVALID");
        } catch (RuntimeException exception) {
            return ItemResult.retryable("SESSION_EXPIRY_EXECUTION_RETRYABLE");
        }
    }

    private static String encode(SessionExpiryPort.Identity identity) {
        return identity.accountId() + "|" + identity.sessionId() + "|" + identity.expiresAt();
    }

    private static SessionExpiryPort.Identity decode(String value) {
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) throw new IllegalArgumentException("invalid item");
        return new SessionExpiryPort.Identity(
                UUID.fromString(parts[0]), UUID.fromString(parts[1]), Instant.parse(parts[2]));
    }
}
