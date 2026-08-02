package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.accountclosure.AccountClosureReadinessProbe;
import com.idea2strategy.backend.application.accountclosure.ClosureDomain;
import com.idea2strategy.backend.application.accountclosure.ClosureReadiness;
import com.idea2strategy.backend.application.accountclosure.ClosureReadinessStatus;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

final class AccountClosureReadinessProbes {
    private AccountClosureReadinessProbes() {}

    static ClosureReadiness readiness(
            ClosureDomain domain, ClosureReadinessStatus status, String reason, int count, Instant at) {
        return new ClosureReadiness(domain, status, reason, "{\"blockingCount\":" + count + "}", at);
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

@Repository
class BotAccountClosureReadinessProbe implements AccountClosureReadinessProbe {
    private final DSLContext dsl;
    private final BotStopCommandPort stops;

    BotAccountClosureReadinessProbe(DSLContext dsl, BotStopCommandPort stops) {
        this.dsl = dsl;
        this.stops = stops;
    }

    @Override public ClosureDomain domain() { return ClosureDomain.BOT; }

    @Override
    @Transactional
    public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
        var botIds = dsl.fetch("select id from bot.bots where owner_account_id = ? and deleted_at is null", accountId)
                .getValues(0, UUID.class);
        for (var botId : botIds) {
            stops.issueOwned(botId, accountId, "ACCOUNT_CLOSING", observedAt);
        }
        int pending = dsl.fetchOne(
                "select count(*) from bot.bots where owner_account_id = ? and deleted_at is null "
                        + "and lifecycle_status <> 'STOPPED'::bot.lifecycle_status", accountId)
                .get(0, Integer.class);
        return AccountClosureReadinessProbes.readiness(domain(),
                pending == 0 ? ClosureReadinessStatus.FROZEN : ClosureReadinessStatus.FREEZE_REQUESTED,
                pending == 0 ? "ALL_BOTS_STOPPED" : "BOT_STOP_PENDING", pending, observedAt);
    }
}

@Repository
class TradingAccountClosureReadinessProbe implements AccountClosureReadinessProbe {
    private final DSLContext dsl;

    TradingAccountClosureReadinessProbe(DSLContext dsl) { this.dsl = dsl; }

    @Override public ClosureDomain domain() { return ClosureDomain.TRADING; }

    @Override
    @Transactional(readOnly = true)
    public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
        int pending = dsl.fetchOne("""
                select
                    (select count(*) from trading.order_state_projections p join bot.bots b on b.id = p.bot_id
                     where b.owner_account_id = ? and p.status in ('PENDING', 'OPEN'))
                  + (select count(*) from trading.resource_reservations r join bot.bots b on b.id = r.bot_id
                     where b.owner_account_id = ? and r.status = 'ACTIVE')
                  + (select count(*) from trading.position_lot_projections p
                     join trading.position_lots lot on lot.id = p.position_lot_id
                     join bot.bots b on b.id = lot.bot_id
                     where b.owner_account_id = ? and (p.remaining_quantity <> 0 or p.active_reserved_quantity <> 0))
                  + (select count(*) from trading.bot_budget_projections p join bot.bots b on b.id = p.bot_id
                     where b.owner_account_id = ? and (p.available_cash_amount <> 0
                        or p.active_reservation_amount <> 0 or p.invested_amount <> 0
                        or p.segregated_short_proceeds_amount <> 0 or p.short_collateral_amount <> 0))
                """, accountId, accountId, accountId, accountId).get(0, Integer.class);
        return AccountClosureReadinessProbes.readiness(domain(),
                pending == 0 ? ClosureReadinessStatus.SETTLED : ClosureReadinessStatus.SETTLEMENT_REQUIRED,
                pending == 0 ? "NO_TRADING_ASSETS" : "TRADING_ASSETS_REMAIN", pending, observedAt);
    }
}

@Repository
class CompetitionAccountClosureReadinessProbe implements AccountClosureReadinessProbe {
    private final DSLContext dsl;

    CompetitionAccountClosureReadinessProbe(DSLContext dsl) { this.dsl = dsl; }

    @Override public ClosureDomain domain() { return ClosureDomain.COMPETITION; }

    @Override
    @Transactional
    public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
        int evaluating = dsl.fetchOne(
                "select count(*) from competition.participations where owner_account_id = ? "
                        + "and status = 'EVALUATING'::competition.participation_status", accountId)
                .get(0, Integer.class);
        if (evaluating > 0) {
            return AccountClosureReadinessProbes.readiness(domain(), ClosureReadinessStatus.BLOCKED,
                    "EVALUATION_FINALIZATION_REQUIRED", evaluating, observedAt);
        }
        var registered = dsl.fetch(
                "select id from competition.participations where owner_account_id = ? "
                        + "and status = 'REGISTERED'::competition.participation_status for update", accountId)
                .getValues(0, UUID.class);
        for (var participationId : registered) {
            dsl.execute("update competition.participations set status = 'WITHDRAWN', withdrawn_at = ?::timestamptz, "
                            + "withdrawal_reason_code = 'ACCOUNT_CLOSING' where id = ?",
                    AccountClosureReadinessProbes.utc(observedAt), participationId);
            dsl.execute("""
                    insert into competition.participation_events
                        (participation_id, event_sequence, event_type, reason_code, occurred_at, payload_document)
                    select ?, coalesce(max(event_sequence), 0) + 1, 'PARTICIPATION_WITHDRAWN',
                           'ACCOUNT_CLOSING', ?::timestamptz, '{"source":"ACCOUNT_CLOSURE"}'::jsonb
                    from competition.participation_events where participation_id = ?
                    """, participationId, AccountClosureReadinessProbes.utc(observedAt), participationId);
        }
        return AccountClosureReadinessProbes.readiness(domain(), ClosureReadinessStatus.FROZEN,
                registered.isEmpty() ? "NO_ACTIVE_PARTICIPATION" : "REGISTERED_WITHDRAWN",
                0, observedAt);
    }
}

@Repository
class NotificationAccountClosureReadinessProbe implements AccountClosureReadinessProbe {
    private final DSLContext dsl;

    NotificationAccountClosureReadinessProbe(DSLContext dsl) { this.dsl = dsl; }

    @Override public ClosureDomain domain() { return ClosureDomain.NOTIFICATION; }

    @Override
    @Transactional
    public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
        dsl.execute("update operations.notification_preferences set enabled = false, updated_at = ?::timestamptz "
                        + "where account_id = ? and enabled",
                AccountClosureReadinessProbes.utc(observedAt), accountId);
        int enabled = dsl.fetchOne(
                "select count(*) from operations.notification_preferences where account_id = ? and enabled", accountId)
                .get(0, Integer.class);
        return AccountClosureReadinessProbes.readiness(domain(),
                enabled == 0 ? ClosureReadinessStatus.FROZEN : ClosureReadinessStatus.BLOCKED,
                enabled == 0 ? "PREFERENCES_DISABLED" : "NOTIFICATION_FREEZE_FAILED", enabled, observedAt);
    }
}

@Repository
class IntegrationAccountClosureReadinessProbe implements AccountClosureReadinessProbe {
    private final DSLContext dsl;

    IntegrationAccountClosureReadinessProbe(DSLContext dsl) { this.dsl = dsl; }

    @Override public ClosureDomain domain() { return ClosureDomain.INTEGRATION; }

    @Override
    @Transactional
    public ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt) {
        dsl.execute("update operations.account_integrations set status = 'CLOSING', "
                        + "freeze_requested_at = coalesce(freeze_requested_at, ?::timestamptz), updated_at = ?::timestamptz "
                        + "where account_id = ? and status = 'ACTIVE'",
                AccountClosureReadinessProbes.utc(observedAt),
                AccountClosureReadinessProbes.utc(observedAt), accountId);
        int pending = dsl.fetchOne(
                "select count(*) from operations.account_integrations where account_id = ? and status <> 'CLOSED'", accountId)
                .get(0, Integer.class);
        return AccountClosureReadinessProbes.readiness(domain(),
                pending == 0 ? ClosureReadinessStatus.FROZEN : ClosureReadinessStatus.FREEZE_REQUESTED,
                pending == 0 ? "NO_ACTIVE_INTEGRATIONS" : "INTEGRATION_CLOSE_PENDING", pending, observedAt);
    }
}
