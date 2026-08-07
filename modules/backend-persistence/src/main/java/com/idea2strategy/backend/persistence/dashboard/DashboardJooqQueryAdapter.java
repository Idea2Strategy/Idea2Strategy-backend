package com.idea2strategy.backend.persistence.dashboard;

import com.idea2strategy.backend.application.dashboard.DashboardBotProjection;
import com.idea2strategy.backend.application.dashboard.DashboardCompetitionProjection;
import com.idea2strategy.backend.application.dashboard.DashboardPerformanceProjection;
import com.idea2strategy.backend.application.dashboard.DashboardQueryPort;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardJooqQueryAdapter implements DashboardQueryPort {
    private final DSLContext dsl;

    public DashboardJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<DashboardBotProjection> findOwned(UUID ownerAccountId) {
        return dsl.fetch(
                        """
                        select b.id as bot_id,
                               b.name as bot_name,
                               b.lifecycle_status::text as lifecycle_status,
                               b.lifecycle_changed_at,
                               b.execution_eligible_from,
                               b.execution_blocked_at,
                               b.execution_block_reason_code,
                               perf.equity_amount,
                               perf.total_return_pct,
                               perf.max_drawdown_pct,
                               perf.sharpe_ratio,
                               perf.calculation_rules_version,
                               perf.updated_at as performance_updated_at,
                               room.id as room_id,
                               room.name as room_name,
                               room.status::text as room_status,
                               participation.status::text as participation_status,
                               schedule.evaluation_ends_at,
                               schedule.timezone_name
                          from bot.bots b
                          left join performance.bot_current_projections perf on perf.bot_id = b.id
                          left join competition.participations participation
                            on participation.bot_id = b.id
                           and participation.status not in (
                               'WITHDRAWN'::competition.participation_status,
                               'EXPELLED'::competition.participation_status)
                          left join competition.rooms room on room.id = participation.room_id
                          left join competition.room_schedules schedule on schedule.room_id = room.id
                         where b.owner_account_id = ?
                           and b.archived_at is null
                           and b.deleted_at is null
                         order by b.created_at desc, b.id asc
                        """,
                        ownerAccountId)
                .map(this::projection);
    }

    private DashboardBotProjection projection(Record record) {
        return new DashboardBotProjection(
                record.get("bot_id", UUID.class),
                record.get("bot_name", String.class),
                BotLifecycleStatus.valueOf(record.get("lifecycle_status", String.class)),
                instant(record, "lifecycle_changed_at"),
                instant(record, "execution_eligible_from"),
                nullableInstant(record, "execution_blocked_at"),
                record.get("execution_block_reason_code", String.class),
                performance(record),
                competition(record));
    }

    private static DashboardPerformanceProjection performance(Record record) {
        BigDecimal equity = record.get("equity_amount", BigDecimal.class);
        if (equity == null) {
            return null;
        }
        return new DashboardPerformanceProjection(
                equity,
                record.get("total_return_pct", BigDecimal.class),
                record.get("max_drawdown_pct", BigDecimal.class),
                record.get("sharpe_ratio", BigDecimal.class),
                record.get("calculation_rules_version", String.class),
                instant(record, "performance_updated_at"));
    }

    private static DashboardCompetitionProjection competition(Record record) {
        UUID roomId = record.get("room_id", UUID.class);
        if (roomId == null) {
            return null;
        }
        return new DashboardCompetitionProjection(
                roomId,
                record.get("room_name", String.class),
                record.get("room_status", String.class),
                record.get("participation_status", String.class),
                instant(record, "evaluation_ends_at"),
                record.get("timezone_name", String.class));
    }

    private static java.time.Instant instant(Record record, String field) {
        return record.get(field, OffsetDateTime.class).toInstant();
    }

    private static java.time.Instant nullableInstant(Record record, String field) {
        OffsetDateTime value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
