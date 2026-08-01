package com.idea2strategy.backend.persistence.strategy;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.strategy.StrategyQueryPort;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class StrategyJooqQueryAdapter implements StrategyQueryPort {
    private final DSLContext dsl;

    public StrategyJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Strategy> findOwnedById(UUID strategyId, UUID ownerAccountId) {
        var strategies = table(name("strategy", "strategies"));
        var id = field(name("id"), UUID.class);
        var owner = field(name("owner_account_id"), UUID.class);
        var mode = field(name("mode"), String.class);
        var strategyName = field(name("name"), String.class);
        var description = field(name("description"), String.class);
        var editSequence = field(name("edit_sequence"), Long.class);
        var createdAt = field(name("created_at"), OffsetDateTime.class);
        var updatedAt = field(name("updated_at"), OffsetDateTime.class);

        return dsl.select(id, owner, mode, strategyName, description, editSequence, createdAt, updatedAt)
                .from(strategies)
                .where(id.eq(strategyId).and(owner.eq(ownerAccountId)))
                .fetchOptional(record -> new Strategy(
                        record.get(id),
                        record.get(owner),
                        StrategyMode.valueOf(record.get(mode)),
                        record.get(strategyName),
                        record.get(description),
                        record.get(editSequence),
                        record.get(createdAt).toInstant(),
                        record.get(updatedAt).toInstant()));
    }
}
