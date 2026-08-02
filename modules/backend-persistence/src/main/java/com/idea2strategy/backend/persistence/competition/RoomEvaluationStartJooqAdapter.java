package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.RoomEvaluationStartPort;
import com.idea2strategy.backend.application.competition.RoomEvaluationStartReport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomEvaluationStartJooqAdapter implements RoomEvaluationStartPort {
    private static final String EVENT_SCHEMA_VERSION = "competition-room.v1";
    private static final String COMMAND_TYPE = "ROOM_EVALUATION_START_COMMAND";
    private final DSLContext dsl;

    public RoomEvaluationStartJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public RoomEvaluationStartReport startEligible(Instant observedAt, int limit) {
        OffsetDateTime observed = observedAt.atOffset(ZoneOffset.UTC);
        var candidates = dsl.fetch(
                "select p.id as participation_id, p.room_id, p.bot_id, "
                        + "b.lifecycle_status::text as lifecycle_status, b.started_at, b.execution_eligible_from, "
                        + "rr.initial_cash_amount as room_initial_cash, rr.fee_policy_id as room_fee_policy_id, "
                        + "rr.buying_power_buffer_policy_id as room_buffer_policy_id, "
                        + "rr.precision_rules_version as room_precision_rules_version, rr.slippage_rate_bps, "
                        + "lc.initial_cash_amount as launch_initial_cash, lc.currency_code, "
                        + "lc.fee_policy_id as launch_fee_policy_id, "
                        + "lc.buying_power_buffer_policy_id as launch_buffer_policy_id, "
                        + "lc.precision_rules_version as launch_precision_rules_version, "
                        + "lc.slippage_rate_bps as launch_slippage_rate_bps, s.snapshot_hash "
                        + "from competition.participations p "
                        + "join competition.rooms r on r.id = p.room_id "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "join bot.bots b on b.id = p.bot_id "
                        + "join bot.launch_configurations lc on lc.bot_id = b.id "
                        + "join bot.launch_snapshots s on s.bot_id = b.id "
                        + "where r.status = 'EVALUATING'::competition.room_status "
                        + "and p.status = 'REGISTERED'::competition.participation_status "
                        + "and p.evaluation_started_at is null "
                        + "and rs.evaluation_starts_at <= ?::timestamptz "
                        + "and b.execution_eligible_from <= ?::timestamptz "
                        + "order by p.joined_at, p.id limit ? for update of p, b skip locked",
                observed, observed, limit);

        for (var candidate : candidates) {
            start(candidate, observed);
        }
        return new RoomEvaluationStartReport(observedAt, candidates.size());
    }

    private void start(Record candidate, OffsetDateTime observedAt) {
        UUID participationId = candidate.get("participation_id", UUID.class);
        UUID roomId = candidate.get("room_id", UUID.class);
        UUID botId = candidate.get("bot_id", UUID.class);
        validateCandidate(candidate, botId);

        String idempotencyKey = "room-evaluation-start:" + participationId;
        UUID botEventId = derivedId("bot-event", participationId);
        UUID correlationId = derivedId("correlation", participationId);
        long botEventSequence = nextBotEventSequence(botId);
        dsl.execute(
                "insert into bot.bot_events "
                        + "(id, bot_id, event_sequence, event_type, event_schema_version, correlation_id, "
                        + "idempotency_key, occurred_at, received_at, summary_document) "
                        + "values (?, ?, ?, 'ROOM_EVALUATION_STARTED', ?, ?, ?, ?::timestamptz, ?::timestamptz, "
                        + "jsonb_build_object('roomId', ?::text, 'participationId', ?::text))",
                botEventId, botId, botEventSequence, EVENT_SCHEMA_VERSION, correlationId, idempotencyKey,
                observedAt, observedAt, roomId, participationId);

        BigDecimal initialCash = candidate.get("room_initial_cash", BigDecimal.class);
        OffsetDateTime officialStartsAt = candidate.get("execution_eligible_from", OffsetDateTime.class);
        UUID cashAccountId = derivedId("cash-account", participationId);
        UUID capitalAccountId = derivedId("capital-account", participationId);
        dsl.execute(
                "insert into trading.ledger_accounts "
                        + "(id, bot_id, account_key, account_type, currency_code, created_at) values "
                        + "(?, ?, ?, 'CASH', 'USD', ?::timestamptz), "
                        + "(?, ?, ?, 'CAPITAL', 'USD', ?::timestamptz)",
                cashAccountId, botId, "room-evaluation:" + participationId + ":cash", observedAt,
                capitalAccountId, botId, "room-evaluation:" + participationId + ":capital", observedAt);

        UUID transactionId = derivedId("initial-capital-transaction", participationId);
        dsl.execute(
                "insert into trading.ledger_transactions "
                        + "(id, bot_id, bot_event_id, transaction_type, transaction_key, source_type, source_id, "
                        + "currency_code, occurred_at, description_code) "
                        + "values (?, ?, ?, 'INITIAL_CAPITAL', ?, 'ROOM_EVALUATION', ?, 'USD', "
                        + "?::timestamptz, 'ROOM_EVALUATION_INITIAL_CAPITAL')",
                transactionId, botId, botEventId, "ROOM_EVALUATION_INITIAL_CAPITAL|" + participationId,
                participationId, observedAt);
        dsl.execute(
                "insert into trading.ledger_entries "
                        + "(id, bot_id, transaction_id, ledger_account_id, entry_sequence, direction, amount, entry_hash) "
                        + "values (?, ?, ?, ?, 1, 'DEBIT'::trading.ledger_direction, ?, ?), "
                        + "(?, ?, ?, ?, 2, 'CREDIT'::trading.ledger_direction, ?, ?)",
                derivedId("cash-entry", participationId), botId, transactionId, cashAccountId, initialCash,
                "room-evaluation-cash:" + participationId,
                derivedId("capital-entry", participationId), botId, transactionId, capitalAccountId, initialCash,
                "room-evaluation-capital:" + participationId);

        dsl.execute(
                "update competition.participations set status = 'EVALUATING'::competition.participation_status, "
                        + "evaluation_started_at = ?::timestamptz where id = ?",
                officialStartsAt, participationId);

        int participationEventSequence = nextParticipationEventSequence(participationId);
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, occurred_at, payload_document) "
                        + "values (?, ?, ?, 'EVALUATION_STARTED', ?::timestamptz, "
                        + "jsonb_build_object('roomId', ?::text, 'botId', ?::text, 'initialCashAmount', ?::text))",
                derivedId("participation-event", participationId), participationId, participationEventSequence,
                observedAt, roomId, botId, initialCash.toPlainString());
        insertStartCommand(candidate, participationId, roomId, botId, observedAt, initialCash, idempotencyKey);
    }

    private void validateCandidate(Record candidate, UUID botId) {
        if (!"RUNNING".equals(candidate.get("lifecycle_status", String.class))
                || candidate.get("started_at", OffsetDateTime.class) != null) {
            throw new IllegalStateException("Room evaluation bot is not in a startable lifecycle state");
        }
        if (!candidate.get("room_initial_cash", BigDecimal.class)
                        .equals(candidate.get("launch_initial_cash", BigDecimal.class))
                || !"USD".equals(candidate.get("currency_code", String.class).trim())
                || !candidate.get("room_fee_policy_id", UUID.class)
                        .equals(candidate.get("launch_fee_policy_id", UUID.class))
                || !candidate.get("room_buffer_policy_id", UUID.class)
                        .equals(candidate.get("launch_buffer_policy_id", UUID.class))
                || !candidate.get("room_precision_rules_version", String.class)
                        .equals(candidate.get("launch_precision_rules_version", String.class))
                || !candidate.get("slippage_rate_bps", Integer.class)
                        .equals(candidate.get("launch_slippage_rate_bps", Integer.class))) {
            throw new IllegalStateException("Room evaluation launch configuration does not match locked room rules");
        }
        Number stateRows = (Number) dsl.fetchValue(
                "select "
                        + "(select count(*) from bot.runtime_state_values where bot_id = ?) + "
                        + "(select count(*) from bot.evaluation_runs where bot_id = ?) + "
                        + "(select count(*) from trading.order_intents where bot_id = ?) + "
                        + "(select count(*) from trading.orders where bot_id = ?) + "
                        + "(select count(*) from trading.order_groups where bot_id = ?) + "
                        + "(select count(*) from trading.fills where bot_id = ?) + "
                        + "(select count(*) from trading.resource_reservations where bot_id = ?) + "
                        + "(select count(*) from trading.position_lots where bot_id = ?) + "
                        + "(select count(*) from trading.flow_position_projections where bot_id = ?) + "
                        + "(select count(*) from trading.ledger_accounts where bot_id = ?) + "
                        + "(select count(*) from trading.ledger_transactions where bot_id = ?)",
                botId, botId, botId, botId, botId, botId, botId, botId, botId, botId, botId);
        if (stateRows.longValue() != 0) {
            throw new IllegalStateException("Room evaluation official state is not empty");
        }
    }

    private void insertStartCommand(
            Record candidate,
            UUID participationId,
            UUID roomId,
            UUID botId,
            OffsetDateTime observedAt,
            BigDecimal initialCash,
            String idempotencyKey) {
        Number nextSequence = (Number) dsl.fetchValue(
                "select coalesce(max(aggregate_sequence), 0) + 1 from operations.outbox_messages "
                        + "where owner_domain = 'competition' and aggregate_id = ?",
                participationId);
        OffsetDateTime eligibleFrom = candidate.get("execution_eligible_from", OffsetDateTime.class);
        String snapshotHash = "sha256:" + candidate.get("snapshot_hash", String.class);
        dsl.execute(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                        + "payload_document, idempotency_key, created_at) "
                        + "values (?, 'competition', ?, ?, ?, ?, "
                        + "jsonb_build_object("
                        + "'metadata', jsonb_build_object('contractVersion', ?, 'messageType', ?, "
                        + "'messageId', ?::text, 'occurredAt', ?::text, 'correlationId', ?::text, "
                        + "'idempotencyKey', ?), "
                        + "'roomId', ?::text, 'participationId', ?::text, 'botId', ?::text, "
                        + "'expectedSnapshotHash', ?, 'executionEligibleFrom', ?::text, "
                        + "'initialCashAmount', ?::text, 'currencyCode', 'USD'), "
                        + "?, ?::timestamptz)",
                derivedId("outbox-message", participationId), participationId, nextSequence.longValue(),
                COMMAND_TYPE, EVENT_SCHEMA_VERSION,
                EVENT_SCHEMA_VERSION, COMMAND_TYPE, derivedId("outbox-message", participationId),
                observedAt.toInstant().toString(), derivedId("correlation", participationId), idempotencyKey,
                roomId, participationId, botId, snapshotHash, eligibleFrom.toInstant().toString(),
                initialCash.toPlainString(), idempotencyKey, observedAt);
    }

    private long nextBotEventSequence(UUID botId) {
        return ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from bot.bot_events where bot_id = ?", botId))
                .longValue();
    }

    private int nextParticipationEventSequence(UUID participationId) {
        return ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?",
                participationId)).intValue();
    }

    private static UUID derivedId(String kind, UUID participationId) {
        return UUID.nameUUIDFromBytes((kind + ":" + participationId).getBytes(StandardCharsets.UTF_8));
    }
}
