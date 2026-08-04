package com.idea2strategy.backend.persistence.competition;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.competition.RoomExecutionPolicyCatalog;
import com.idea2strategy.backend.application.competition.RoomExecutionPolicyCatalogQueryPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class RoomExecutionPolicyCatalogJooqQueryAdapter implements RoomExecutionPolicyCatalogQueryPort {
    private final DSLContext dsl;

    public RoomExecutionPolicyCatalogJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public RoomExecutionPolicyCatalog findSelectableAt(Instant at) {
        var observedAt = at.atOffset(ZoneOffset.UTC);
        var fee = table(name("trading", "fee_policy_versions")).as("fee");
        var feeId = field(name("fee", "id"), UUID.class);
        var feeCode = field(name("fee", "policy_code"), String.class);
        var feeVersion = field(name("fee", "version"), String.class);
        var feeRate = field(name("fee", "fee_rate_bps"), Integer.class);
        var calculationVersion = field(name("fee", "calculation_rules_version"), String.class);
        var feeHash = field(name("fee", "rules_hash"), String.class);
        var feeFrom = field(name("fee", "effective_from"), OffsetDateTime.class);
        var feeTo = field(name("fee", "effective_to"), OffsetDateTime.class);
        var feePublished = field(name("fee", "published_at"), OffsetDateTime.class);

        var fees = dsl.select(
                        feeId,
                        feeCode,
                        feeVersion,
                        feeRate,
                        calculationVersion,
                        feeHash,
                        feeFrom,
                        feeTo,
                        feePublished)
                .from(fee)
                .where(feePublished.le(observedAt))
                .and(feeFrom.le(observedAt))
                .and(feeTo.isNull().or(feeTo.gt(observedAt)))
                .orderBy(feeCode, feeVersion)
                .fetch(record -> new RoomExecutionPolicyCatalog.FeePolicyVersion(
                        record.get(feeId),
                        record.get(feeCode),
                        record.get(feeVersion),
                        record.get(feeRate),
                        record.get(calculationVersion),
                        record.get(feeHash),
                        record.get(feeFrom).toInstant(),
                        instant(record.get(feeTo)),
                        record.get(feePublished).toInstant()));

        var buffer = table(name("trading", "buying_power_buffer_policy_versions")).as("buffer");
        var bufferId = field(name("buffer", "id"), UUID.class);
        var bufferCode = field(name("buffer", "policy_code"), String.class);
        var bufferVersion = field(name("buffer", "version"), String.class);
        var bufferRate = field(name("buffer", "buffer_bps"), Integer.class);
        var roundingVersion = field(name("buffer", "rounding_rules_version"), String.class);
        var bufferHash = field(name("buffer", "rules_hash"), String.class);
        var bufferFrom = field(name("buffer", "effective_from"), OffsetDateTime.class);
        var bufferTo = field(name("buffer", "effective_to"), OffsetDateTime.class);
        var bufferPublished = field(name("buffer", "published_at"), OffsetDateTime.class);

        var buffers = dsl.select(
                        bufferId,
                        bufferCode,
                        bufferVersion,
                        bufferRate,
                        roundingVersion,
                        bufferHash,
                        bufferFrom,
                        bufferTo,
                        bufferPublished)
                .from(buffer)
                .where(bufferPublished.le(observedAt))
                .and(bufferFrom.le(observedAt))
                .and(bufferTo.isNull().or(bufferTo.gt(observedAt)))
                .orderBy(bufferCode, bufferVersion)
                .fetch(record -> new RoomExecutionPolicyCatalog.BuyingPowerBufferPolicyVersion(
                        record.get(bufferId),
                        record.get(bufferCode),
                        record.get(bufferVersion),
                        record.get(bufferRate),
                        record.get(roundingVersion),
                        record.get(bufferHash),
                        record.get(bufferFrom).toInstant(),
                        instant(record.get(bufferTo)),
                        record.get(bufferPublished).toInstant()));

        return new RoomExecutionPolicyCatalog(fees, buffers);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
