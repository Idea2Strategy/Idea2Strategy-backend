package com.idea2strategy.backend.persistence.identity;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.identity.AccountLifecycleCandidateQueryPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleSnapshot;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class AccountLifecycleJooqQueryAdapter implements AccountLifecycleCandidateQueryPort {
    private final DSLContext dsl;

    public AccountLifecycleJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<AccountLifecycleSnapshot> findActiveDormancyCandidates(Instant candidateCutoff, int limit) {
        Objects.requireNonNull(candidateCutoff, "candidateCutoff");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }

        var accounts = table(name("identity", "accounts")).as("account");
        var accountId = field(name("account", "id"), UUID.class);
        var status = field(name("account", "lifecycle_status")).cast(String.class);
        var version = field(name("account", "lifecycle_version"), Long.class);
        var lastSuccessfulAuthAt = field(name("account", "last_successful_auth_at"), OffsetDateTime.class);
        var withdrawalRequestedAt = field(name("account", "withdrawal_requested_at"), OffsetDateTime.class);
        var cancellationDeadlineAt = field(name("account", "cancellation_deadline_at"), OffsetDateTime.class);
        var closingPreviousStatus = field(name("account", "closing_previous_status")).cast(String.class);

        return dsl.select(
                        accountId,
                        status,
                        version,
                        lastSuccessfulAuthAt,
                        withdrawalRequestedAt,
                        cancellationDeadlineAt,
                        closingPreviousStatus)
                .from(accounts)
                .where(status.eq(AccountLifecycleStatus.ACTIVE.name())
                        .and(lastSuccessfulAuthAt.isNotNull())
                        .and(lastSuccessfulAuthAt.le(candidateCutoff.atOffset(ZoneOffset.UTC))))
                .orderBy(lastSuccessfulAuthAt.asc(), accountId.asc())
                .limit(limit)
                .fetch(record -> new AccountLifecycleSnapshot(
                        record.value1(),
                        AccountLifecycleStatus.valueOf(record.value2()),
                        record.value3(),
                        instant(record.value4()),
                        instant(record.value5()),
                        instant(record.value6()),
                        record.value7() == null
                                ? null
                                : AccountLifecycleStatus.valueOf(record.value7())));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
