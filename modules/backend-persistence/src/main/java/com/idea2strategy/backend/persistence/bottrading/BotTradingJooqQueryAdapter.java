package com.idea2strategy.backend.persistence.bottrading;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.bottrading.BotBudgetView;
import com.idea2strategy.backend.application.bottrading.BotDecisionReasonView;
import com.idea2strategy.backend.application.bottrading.BotFillView;
import com.idea2strategy.backend.application.bottrading.BotOrderView;
import com.idea2strategy.backend.application.bottrading.BotPositionView;
import com.idea2strategy.backend.application.bottrading.BotStopSettlementView;
import com.idea2strategy.backend.application.bottrading.BotTradingQueryPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/**
 * Reads the canonical trading tables the trading engine writes.
 *
 * <p>Ownership is checked once per call against {@code bot.bots.owner_account_id} rather than being
 * folded into each query. The bot id arrives from the client, so a query that only filtered on it
 * would happily answer for somebody else's bot. Keeping the check separate also preserves a
 * distinction that matters: a bot which is not the caller's and a bot which simply has not traded
 * yet must not be told apart. An absent {@link Optional} is the first, an empty list the second.
 */
@Repository
public class BotTradingJooqQueryAdapter implements BotTradingQueryPort {

    private final DSLContext dsl;

    public BotTradingJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<List<BotOrderView>> findOwnedOrders(UUID botId, UUID ownerAccountId, int limit) {
        return owned(botId, ownerAccountId, () -> dsl
                .select(
                        field(name("o", "id"), UUID.class),
                        field(name("o", "partition_id"), UUID.class),
                        field(name("o", "instrument_id"), UUID.class),
                        field(name("o", "side"), String.class),
                        field(name("o", "order_type"), String.class),
                        field(name("o", "time_in_force"), String.class),
                        field(name("o", "requested_quantity"), BigDecimal.class),
                        field(name("p", "filled_quantity"), BigDecimal.class),
                        field(name("p", "remaining_quantity"), BigDecimal.class),
                        field(name("p", "status"), String.class),
                        field(name("o", "accepted_at"), OffsetDateTime.class))
                .from(table(name("trading", "orders")).as("o"))
                .join(table(name("trading", "order_state_projections")).as("p"))
                .on(field(name("p", "order_id"), UUID.class).eq(field(name("o", "id"), UUID.class)))
                .where(field(name("o", "bot_id"), UUID.class).eq(botId))
                .orderBy(field(name("o", "accepted_at")).desc(), field(name("o", "id")).asc())
                .limit(limit)
                .fetch(record -> new BotOrderView(
                        record.value1(), record.value2(), record.value3(), record.value4(),
                        record.value5(), record.value6(), record.value7(), record.value8(),
                        record.value9(), record.value10(), instant(record.value11()))));
    }

    @Override
    public Optional<List<BotFillView>> findOwnedFills(UUID botId, UUID ownerAccountId, int limit) {
        return owned(botId, ownerAccountId, () -> dsl
                .select(
                        field(name("f", "id"), UUID.class),
                        field(name("f", "order_id"), UUID.class),
                        field(name("o", "instrument_id"), UUID.class),
                        field(name("f", "quantity"), BigDecimal.class),
                        field(name("f", "fill_price"), BigDecimal.class),
                        field(name("f", "gross_amount"), BigDecimal.class),
                        field(name("f", "fee_amount"), BigDecimal.class),
                        field(name("f", "settlement_cash_delta"), BigDecimal.class),
                        field(name("f", "occurred_at"), OffsetDateTime.class))
                .from(table(name("trading", "fills")).as("f"))
                .join(table(name("trading", "orders")).as("o"))
                .on(field(name("o", "id"), UUID.class).eq(field(name("f", "order_id"), UUID.class)))
                .where(field(name("f", "bot_id"), UUID.class).eq(botId))
                .orderBy(field(name("f", "occurred_at")).desc(), field(name("f", "id")).asc())
                .limit(limit)
                .fetch(record -> new BotFillView(
                        record.value1(), record.value2(), record.value3(), record.value4(),
                        record.value5(), record.value6(), record.value7(), record.value8(),
                        instant(record.value9()))));
    }

    @Override
    public Optional<List<BotPositionView>> findOwnedPositions(UUID botId, UUID ownerAccountId) {
        return owned(botId, ownerAccountId, () -> dsl
                .select(
                        field(name("flow_id"), UUID.class),
                        field(name("partition_id"), UUID.class),
                        field(name("instrument_id"), UUID.class),
                        field(name("long_quantity"), BigDecimal.class),
                        field(name("short_quantity"), BigDecimal.class),
                        field(name("cost_basis_amount"), BigDecimal.class),
                        field(name("last_event_sequence"), Long.class))
                .from(table(name("trading", "flow_position_projections")))
                .where(field(name("bot_id"), UUID.class).eq(botId))
                .orderBy(field(name("flow_id")).asc(), field(name("instrument_id")).asc())
                .fetch(record -> new BotPositionView(
                        record.value1(), record.value2(), record.value3(), record.value4(),
                        record.value5(), record.value6(), record.value7())));
    }

    /**
     * The budget is two projections, and a bot that has not traded yet has neither.
     *
     * <p>Reporting that absence as "no such bot" would be wrong, so an owned bot without a
     * projection row answers with an unvalued budget rather than nothing at all.
     */
    @Override
    public Optional<BotBudgetView> findOwnedBudget(UUID botId, UUID ownerAccountId) {
        if (!isOwned(botId, ownerAccountId)) {
            return Optional.empty();
        }
        List<BotBudgetView.PartitionBudget> partitions = dsl
                .select(
                        field(name("partition_id"), UUID.class),
                        field(name("budget_cap_amount"), BigDecimal.class),
                        field(name("active_reservation_amount"), BigDecimal.class),
                        field(name("invested_amount"), BigDecimal.class))
                .from(table(name("trading", "partition_budget_projections")))
                .where(field(name("bot_id"), UUID.class).eq(botId))
                .orderBy(field(name("partition_id")).asc())
                .fetch(record -> new BotBudgetView.PartitionBudget(
                        record.value1(), record.value2(), record.value3(), record.value4()));

        Optional<BotBudgetView> stored = dsl
                .select(
                        field(name("currency_code"), String.class),
                        field(name("available_cash_amount"), BigDecimal.class),
                        field(name("active_reservation_amount"), BigDecimal.class),
                        field(name("invested_amount"), BigDecimal.class),
                        field(name("valuation_at"), OffsetDateTime.class),
                        field(name("valuation_status"), String.class),
                        field(name("last_event_sequence"), Long.class))
                .from(table(name("trading", "bot_budget_projections")))
                .where(field(name("bot_id"), UUID.class).eq(botId))
                .fetchOptional(record -> new BotBudgetView(
                        record.value1(), record.value2(), record.value3(), record.value4(),
                        instant(record.value5()), record.value6(), record.value7(), partitions));

        return Optional.of(stored.orElseGet(() -> new BotBudgetView(
                null, null, null, null, null, "UNVALUED", 0L, partitions)));
    }

    @Override
    public Optional<List<BotDecisionReasonView>> findOwnedDecisionReasons(
            UUID botId, UUID ownerAccountId, int limit) {
        return owned(botId, ownerAccountId, () -> dsl
                .select(
                        field(name("i", "id"), UUID.class),
                        field(name("i", "partition_id"), UUID.class),
                        field(name("i", "flow_id"), UUID.class),
                        field(name("i", "instrument_id"), UUID.class),
                        field(name("i", "decision"), String.class),
                        field(name("i", "decision_reason_code"), String.class),
                        field(name("i", "requested_quantity"), BigDecimal.class),
                        field(name("i", "final_quantity"), BigDecimal.class),
                        field(name("b", "finalized_at"), OffsetDateTime.class))
                .from(table(name("trading", "order_intents")).as("i"))
                .join(table(name("trading", "order_intent_batches")).as("b"))
                .on(field(name("b", "id"), UUID.class).eq(field(name("i", "batch_id"), UUID.class)))
                .where(field(name("i", "bot_id"), UUID.class).eq(botId)
                        // Only what was refused or cut down. An intent that went through whole
                        // carries no reason the owner needs explaining.
                        //
                        // decision is a PostgreSQL enum, so it is cast before being compared with a
                        // string bind; the untyped comparison has no operator and fails at runtime.
                        .and(field(name("i", "decision")).cast(SQLDataType.VARCHAR).ne("APPROVED")
                                .or(field(name("i", "final_quantity"), BigDecimal.class)
                                        .lt(field(name("i", "requested_quantity"), BigDecimal.class)))))
                .orderBy(field(name("b", "finalized_at")).desc(), field(name("i", "id")).asc())
                .limit(limit)
                .fetch(record -> new BotDecisionReasonView(
                        record.value1(), record.value2(), record.value3(), record.value4(),
                        record.value5(), record.value6(), record.value7(), record.value8(),
                        instant(record.value9()))));
    }

    @Override
    public Optional<List<BotStopSettlementView>> findOwnedStopSettlement(
            UUID botId, UUID ownerAccountId) {
        return owned(botId, ownerAccountId, () -> dsl
                .select(
                        field(name("id"), UUID.class),
                        field(name("partition_id"), UUID.class),
                        field(name("flow_id"), UUID.class),
                        field(name("instrument_id"), UUID.class),
                        field(name("reason_type"), String.class),
                        field(name("requested_quantity"), BigDecimal.class),
                        field(name("generated_intent_id"), UUID.class),
                        field(name("created_at"), OffsetDateTime.class))
                .from(table(name("trading", "system_close_actions")))
                .where(field(name("bot_id"), UUID.class).eq(botId))
                .orderBy(field(name("created_at")).desc(), field(name("id")).asc())
                .fetch(record -> new BotStopSettlementView(
                        record.value1(), record.value2(), record.value3(), record.value4(),
                        record.value5(), record.value6(), record.value7(),
                        instant(record.value8()))));
    }

    private <T> Optional<T> owned(UUID botId, UUID ownerAccountId, Supplier<T> read) {
        return isOwned(botId, ownerAccountId) ? Optional.of(read.get()) : Optional.empty();
    }

    private boolean isOwned(UUID botId, UUID ownerAccountId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(table(name("bot", "bots")))
                        .where(field(name("id"), UUID.class).eq(botId)
                                .and(field(name("owner_account_id"), UUID.class).eq(ownerAccountId))
                                .and(field(name("deleted_at")).isNull())));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
