package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.delegation.DelegatedCredentialExpiryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class DelegatedCredentialExpiryBatchCategoryPort implements BatchCategoryPort {
    private final DelegatedCredentialExpiryPort credentials;
    private final JdbcTemplate jdbc;

    public DelegatedCredentialExpiryBatchCategoryPort(
            DelegatedCredentialExpiryPort credentials, JdbcTemplate jdbc) {
        this.credentials = credentials;
        this.jdbc = jdbc;
    }

    @Override public BatchCategory category() { return BatchCategory.DELEGATED_TOKEN; }

    @Override
    public ClaimPage claimDue(ClaimRequest request) {
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        List<WorkItem> items = credentials.findDueCredentials(request.limit()).stream()
                .map(identity -> new WorkItem(
                        category(), encode(identity), identity.expiresAt(),
                        (identity.kind() == DelegatedCredentialExpiryPort.Kind.CREDENTIAL
                                ? "delegated-token-expiry:" + identity.credentialId()
                                : "delegated-authorization-expiry:" + identity.authorizationId())
                                + ":" + identity.expiresAt(),
                        UUID.randomUUID(), 1))
                .toList();
        Cursor next = items.isEmpty() ? null : new Cursor(items.getLast().dueAt(), items.getLast().itemId());
        return new ClaimPage(databaseNow, items, next);
    }

    @Override
    public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
        try {
            return credentials.expire(decode(item.itemId()), correlationId)
                            == DelegatedCredentialExpiryPort.Result.APPLIED
                    ? ItemResult.completed()
                    : ItemResult.alreadyCompleted();
        } catch (IllegalArgumentException exception) {
            return ItemResult.permanent("DELEGATED_TOKEN_EXPIRY_BATCH_ITEM_INVALID");
        } catch (RuntimeException exception) {
            return ItemResult.retryable("DELEGATED_TOKEN_EXPIRY_EXECUTION_RETRYABLE");
        }
    }

    private static String encode(DelegatedCredentialExpiryPort.Identity identity) {
        return identity.kind() + "|" + identity.authorizationId() + "|"
                + (identity.credentialId() == null ? "-" : identity.credentialId()) + "|" + identity.expiresAt();
    }

    private static DelegatedCredentialExpiryPort.Identity decode(String value) {
        String[] parts = value.split("\\|", 4);
        if (parts.length != 4) throw new IllegalArgumentException("invalid item");
        return new DelegatedCredentialExpiryPort.Identity(
                DelegatedCredentialExpiryPort.Kind.valueOf(parts[0]),
                UUID.fromString(parts[1]), "-".equals(parts[2]) ? null : UUID.fromString(parts[2]),
                Instant.parse(parts[3]));
    }
}
