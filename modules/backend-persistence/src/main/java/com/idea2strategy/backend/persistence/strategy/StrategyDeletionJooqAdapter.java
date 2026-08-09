package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.StrategyDeletionCommandPort;
import com.idea2strategy.backend.application.strategy.StrategyDeletionResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StrategyDeletionJooqAdapter implements StrategyDeletionCommandPort {
    private final DSLContext dsl;

    public StrategyDeletionJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public StrategyDeletionResult deleteOwned(
            UUID strategyId, UUID ownerAccountId, Instant deletedAt) {
        dsl.fetchOne("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", strategyId);
        var strategy = dsl.fetchOne(
                "select deleted_at from strategy.strategies "
                        + "where id = ? and owner_account_id = ? for update",
                strategyId,
                ownerAccountId);
        if (strategy == null) {
            return StrategyDeletionResult.NOT_FOUND;
        }
        if (strategy.get("deleted_at", OffsetDateTime.class) != null) {
            return StrategyDeletionResult.ALREADY_DELETED;
        }

        dsl.execute("delete from strategy.strategy_edit_leases where strategy_id = ?", strategyId);
        dsl.execute(
                "update strategy.strategies set deleted_at = ?::timestamptz, "
                        + "updated_at = ?::timestamptz, delegated_access_epoch = delegated_access_epoch + 1 "
                        + "where id = ?",
                utc(deletedAt),
                utc(deletedAt),
                strategyId);
        return StrategyDeletionResult.DELETED;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
