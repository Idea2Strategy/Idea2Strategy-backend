package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.caseoperations.CaseResponseDeadlinePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class CaseDeadlineBatchCategoryPort implements BatchCategoryPort {
    private final CaseResponseDeadlinePort deadlines;
    private final JdbcTemplate jdbc;

    public CaseDeadlineBatchCategoryPort(CaseResponseDeadlinePort deadlines, JdbcTemplate jdbc) {
        this.deadlines = deadlines;
        this.jdbc = jdbc;
    }

    @Override public BatchCategory category() { return BatchCategory.CASE_DEADLINE; }

    @Override
    public ClaimPage claimDue(ClaimRequest request) {
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        List<WorkItem> items = deadlines.findDue(request.limit()).stream()
                .map(identity -> new WorkItem(
                        category(), encode(identity), identity.responseDeadlineAt(),
                        "case-deadline:" + identity.caseId() + ":"
                                + identity.expectedCaseVersion() + ":" + identity.responseDeadlineAt(),
                        UUID.randomUUID(), 1))
                .toList();
        Cursor next = items.isEmpty() ? null
                : new Cursor(items.getLast().dueAt(), items.getLast().itemId());
        return new ClaimPage(databaseNow, items, next);
    }

    @Override
    public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
        try {
            CaseResponseDeadlinePort.Result result = deadlines.expire(decode(item.itemId()), correlationId);
            return result.status() == CaseResponseDeadlinePort.Result.Status.APPLIED
                    ? ItemResult.completed()
                    : ItemResult.alreadyCompleted();
        } catch (IllegalArgumentException exception) {
            return ItemResult.permanent("CASE_DEADLINE_BATCH_ITEM_INVALID");
        } catch (RuntimeException exception) {
            return ItemResult.retryable("CASE_DEADLINE_EXECUTION_RETRYABLE");
        }
    }

    private static String encode(CaseResponseDeadlinePort.Identity identity) {
        return identity.caseId() + "|" + identity.expectedCaseVersion() + "|"
                + identity.responseDeadlineAt();
    }

    private static CaseResponseDeadlinePort.Identity decode(String value) {
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) throw new IllegalArgumentException("invalid item");
        return new CaseResponseDeadlinePort.Identity(
                UUID.fromString(parts[0]), Long.parseLong(parts[1]), Instant.parse(parts[2]));
    }
}
