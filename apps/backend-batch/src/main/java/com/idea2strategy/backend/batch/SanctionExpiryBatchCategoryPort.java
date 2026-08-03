package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommand;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionExpiryPort;
import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class SanctionExpiryBatchCategoryPort implements BatchCategoryPort {
    private final AccountSanctionExpiryPort due;
    private final AccountSanctionCommandService commands;
    private final JdbcTemplate jdbc;

    public SanctionExpiryBatchCategoryPort(
            AccountSanctionExpiryPort due, AccountSanctionCommandService commands, JdbcTemplate jdbc) {
        this.due = due;
        this.commands = commands;
        this.jdbc = jdbc;
    }

    @Override public BatchCategory category() { return BatchCategory.SANCTION; }

    @Override
    public ClaimPage claimDue(ClaimRequest request) {
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        List<WorkItem> items = due.findDue(request.limit()).stream().map(candidate -> {
            String itemId = candidate.accountId() + "|" + candidate.sanctionId() + "|"
                    + candidate.aggregateVersion() + "|" + candidate.expiresAt();
            return new WorkItem(category(), itemId, candidate.expiresAt(),
                    "sanction-expiry:" + candidate.sanctionId() + ":" + candidate.expiresAt(),
                    UUID.randomUUID(), 1);
        }).toList();
        Cursor next = items.isEmpty() ? null
                : new Cursor(items.getLast().dueAt(), items.getLast().itemId());
        return new ClaimPage(databaseNow, items, next);
    }

    @Override
    public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
        String[] values = item.itemId().split("\\|", 4);
        if (values.length != 4) return ItemResult.permanent("SANCTION_BATCH_ITEM_INVALID");
        AccountSanctionCommand command = new AccountSanctionCommand(
                AccountSanctionCommand.Type.EXPIRE, null, UUID.fromString(values[0]),
                UUID.fromString(values[1]), null, "SANCTION_EXPIRED", null, null,
                correlationId, item.idempotencyKey(), sha256(item.idempotencyKey()),
                Long.parseLong(values[2]));
        var result = commands.execute(command);
        return switch (result.status()) {
            case APPLIED -> ItemResult.completed();
            case NO_OP -> ItemResult.alreadyCompleted();
            case REJECTED -> result.code().contains("VERSION")
                    ? ItemResult.retryable(result.code()) : ItemResult.permanent(result.code());
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
