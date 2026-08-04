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
                        + "c.initial_cash_amount, c.broker_rules_version, c.accounting_rules_version, "
                        + "c.precision_rules_version, c.fee_policy_id, c.slippage_rate_bps, "
                        + "c.buying_power_buffer_policy_id, c.configuration_hash "
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
        var policy = dsl.fetchOne(
                "select policy_artifact_hash from backtest.execution_policy_versions "
                        + "where version = ? and locked_at <= current_timestamp",
                command.executionPolicyVersion());
        if (policy == null || policy.get("policy_artifact_hash", String.class) == null) {
            throw new IllegalStateException("Locked backtest execution policy was not found");
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
                command.executionPolicyVersion(),
                command.idempotencyKey(),
                occurredAt);
        dsl.execute("""
                insert into backtest.runs (
                    id, lane, message_id, bot_id, owner_account_id, configuration_hash,
                    canonical_payload_hash, aggregate_sequence, status, evaluation_start, evaluation_end,
                    initial_cash_amount, market_rules_version, accounting_rules_version,
                    execution_policy_version, precision_rules_version, fee_policy_id, slippage_rate_bps,
                    buying_power_buffer_policy_id, idempotency_scope, idempotency_key, queued_at)
                values (?, 'CUSTOM', ?, ?, ?, ?,
                    encode(sha256(convert_to((?::jsonb)::text, 'UTF8')), 'hex'),
                    1, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                on conflict (lane, idempotency_scope, idempotency_key) do nothing
                """,
                request.aggregateId(), request.messageId(), command.botId(), accountId,
                context.get("configuration_hash", String.class), request.payloadDocument(),
                command.periodStart(), command.periodEnd(), context.get("initial_cash_amount", BigDecimal.class),
                context.get("broker_rules_version", String.class),
                context.get("accounting_rules_version", String.class), command.executionPolicyVersion(),
                context.get("precision_rules_version", String.class), context.get("fee_policy_id", UUID.class),
                context.get("slippage_rate_bps", Integer.class),
                context.get("buying_power_buffer_policy_id", UUID.class), accountId.toString(),
                command.idempotencyKey(), occurredAt.atOffset(java.time.ZoneOffset.UTC));
        return outbox.enqueue(request, occurredAt);
    }

    private static String prefixed(String value) {
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

}
