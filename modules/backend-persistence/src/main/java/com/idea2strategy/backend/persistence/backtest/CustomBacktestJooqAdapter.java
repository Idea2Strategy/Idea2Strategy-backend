package com.idea2strategy.backend.persistence.backtest;

import com.idea2strategy.backend.application.backtest.BacktestRequestEnvelope;
import com.idea2strategy.backend.application.backtest.BacktestRequestReceipt;
import com.idea2strategy.backend.application.backtest.CustomBacktestCommand;
import com.idea2strategy.backend.application.backtest.CustomBacktestCommandPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CustomBacktestJooqAdapter implements CustomBacktestCommandPort {
    private final DSLContext dsl;
    private final BacktestRequestOutboxStore outbox;

    public CustomBacktestJooqAdapter(DSLContext dsl, BacktestRequestOutboxStore outbox) {
        this.dsl = dsl;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public BacktestRequestReceipt enqueue(UUID accountId, CustomBacktestCommand command, Instant occurredAt) {
        var context = dsl.fetchOne(
                "select s.snapshot_hash, p.plan_checksum, "
                        + "p.plan_document ->> 'instrumentCatalogVersion' as instrument_catalog_version, "
                        + "c.initial_cash_amount, c.accounting_rules_version "
                        + "from bot.bots b join bot.launch_snapshots s on s.bot_id = b.id "
                        + "join bot.launch_contract_plans p on p.bot_id = b.id "
                        + "join bot.launch_configurations c on c.bot_id = b.id "
                        + "where b.id = ? and b.owner_account_id = ? for share of b",
                command.botId(), accountId);
        if (context == null) {
            throw new NoSuchElementException("Bot not found");
        }
        var dataset = dsl.fetchOne(
                "select dataset_hash from market_data.dataset_manifests where id = ? and status = 'AVAILABLE' "
                        + "and available_at is not null and period_start::date <= ? and period_end::date >= ?",
                command.datasetManifestId(), command.periodStart(), command.periodEnd());
        if (dataset == null) {
            throw new IllegalStateException("Requested period is not covered by an available dataset");
        }
        var request = BacktestRequestEnvelope.custom(
                accountId,
                command.botId(),
                command.datasetManifestId(),
                prefixed(dataset.get("dataset_hash", String.class)),
                command.periodStart(),
                command.periodEnd(),
                prefixed(context.get("snapshot_hash", String.class)),
                prefixed(context.get("plan_checksum", String.class)),
                context.get("instrument_catalog_version", String.class),
                context.get("initial_cash_amount", BigDecimal.class),
                context.get("accounting_rules_version", String.class),
                command.idempotencyKey(),
                occurredAt);
        return outbox.enqueue(request, occurredAt);
    }

    private static String prefixed(String value) {
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }
}
