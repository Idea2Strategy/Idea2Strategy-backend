package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.identity.RefreshTokenFamilyExpiryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class RefreshTokenFamilyExpiryBatchCategoryPort implements BatchCategoryPort {
    private final RefreshTokenFamilyExpiryPort families;
    private final JdbcTemplate jdbc;

    public RefreshTokenFamilyExpiryBatchCategoryPort(RefreshTokenFamilyExpiryPort families, JdbcTemplate jdbc) {
        this.families = families;
        this.jdbc = jdbc;
    }

    @Override public BatchCategory category() { return BatchCategory.REFRESH_TOKEN_FAMILY; }

    @Override
    public ClaimPage claimDue(ClaimRequest request) {
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        List<WorkItem> items = families.findDueRefreshTokenFamilies(request.limit()).stream()
                .map(identity -> new WorkItem(
                        category(), encode(identity), identity.expiresAt(),
                        "refresh-token-family-expiry:" + identity.familyId() + ":" + identity.expiresAt(),
                        UUID.randomUUID(), 1))
                .toList();
        Cursor next = items.isEmpty() ? null : new Cursor(items.getLast().dueAt(), items.getLast().itemId());
        return new ClaimPage(databaseNow, items, next);
    }

    @Override
    public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
        try {
            return families.expire(decode(item.itemId()), correlationId) == RefreshTokenFamilyExpiryPort.Result.APPLIED
                    ? ItemResult.completed()
                    : ItemResult.alreadyCompleted();
        } catch (IllegalArgumentException exception) {
            return ItemResult.permanent("REFRESH_TOKEN_FAMILY_EXPIRY_BATCH_ITEM_INVALID");
        } catch (RuntimeException exception) {
            return ItemResult.retryable("REFRESH_TOKEN_FAMILY_EXPIRY_EXECUTION_RETRYABLE");
        }
    }

    private static String encode(RefreshTokenFamilyExpiryPort.Identity identity) {
        return identity.accountId() + "|" + identity.familyId() + "|" + identity.expiresAt();
    }

    private static RefreshTokenFamilyExpiryPort.Identity decode(String value) {
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) throw new IllegalArgumentException("invalid item");
        return new RefreshTokenFamilyExpiryPort.Identity(
                UUID.fromString(parts[0]), UUID.fromString(parts[1]), Instant.parse(parts[2]));
    }
}
