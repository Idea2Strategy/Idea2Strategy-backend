package com.idea2strategy.backend.persistence.strategy;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.when;

import com.idea2strategy.backend.application.strategy.StrategyLibraryItem;
import com.idea2strategy.backend.application.strategy.StrategyLibraryItemKind;
import com.idea2strategy.backend.application.strategy.StrategyLibraryPosition;
import com.idea2strategy.backend.application.strategy.StrategyLibraryQueryPort;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class StrategyLibraryJooqQueryAdapter implements StrategyLibraryQueryPort {
    private static final Comparator<StrategyLibraryItem> ITEM_ORDER = Comparator
            .comparing(StrategyLibraryItem::updatedAt)
            .reversed()
            .thenComparing(StrategyLibraryItem::kind)
            .thenComparing(StrategyLibraryItem::id);

    private final DSLContext dsl;

    public StrategyLibraryJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<StrategyLibraryItem> findVisible(
            UUID ownerAccountId,
            Instant snapshotAt,
            StrategyLibraryPosition after,
            int limit) {
        OffsetDateTime snapshot = snapshotAt.atOffset(ZoneOffset.UTC);
        List<StrategyLibraryItem> items = new ArrayList<>();
        items.addAll(findDrafts(ownerAccountId, snapshot, after, limit));
        items.addAll(findReleased(ownerAccountId, snapshot, after, limit));
        items.addAll(findPackages(snapshot, after, limit));
        items.addAll(findTemplates(snapshot, after, limit));
        return items.stream().sorted(ITEM_ORDER).limit(limit).toList();
    }

    private List<StrategyLibraryItem> findDrafts(
            UUID ownerAccountId, OffsetDateTime snapshot, StrategyLibraryPosition after, int limit) {
        var strategies = table(name("strategy", "strategies")).as("s");
        var validations = table(name("strategy", "validation_runs")).as("v");
        Field<UUID> id = field(name("s", "id"), UUID.class);
        Field<UUID> owner = field(name("s", "owner_account_id"), UUID.class);
        Field<String> mode = field(name("s", "mode"), String.class);
        Field<String> strategyName = field(name("s", "name"), String.class);
        Field<String> description = field(name("s", "description"), String.class);
        Field<OffsetDateTime> updatedAt = field(name("s", "updated_at"), OffsetDateTime.class);
        Field<OffsetDateTime> archivedAt = field(name("s", "archived_at"), OffsetDateTime.class);
        Field<OffsetDateTime> deletedAt = field(name("s", "deleted_at"), OffsetDateTime.class);
        Field<UUID> validationStrategyId = field(name("v", "strategy_id"), UUID.class);
        Field<String> validationState = field(name("v", "status"), String.class);
        Field<OffsetDateTime> validationRequestedAt = field(name("v", "requested_at"), OffsetDateTime.class);
        Field<String> latestValidation = select(validationState)
                .from(validations)
                .where(validationStrategyId.eq(id).and(validationRequestedAt.le(snapshot)))
                .orderBy(validationRequestedAt.desc())
                .limit(1)
                .asField("validation_status");

        return dsl.select(
                        id,
                        mode,
                        strategyName,
                        description,
                        when(archivedAt.isNull(), inline("DRAFT")).otherwise(inline("ARCHIVED")),
                        latestValidation,
                        updatedAt,
                        archivedAt)
                .from(strategies)
                .where(owner.eq(ownerAccountId)
                        .and(deletedAt.isNull())
                        .and(updatedAt.le(snapshot))
                        .and(afterCondition(updatedAt, StrategyLibraryItemKind.DRAFT, id, after)))
                .orderBy(updatedAt.desc(), id.asc())
                .limit(limit)
                .fetch(record -> new StrategyLibraryItem(
                        record.get(id),
                        StrategyLibraryItemKind.DRAFT,
                        StrategyMode.valueOf(record.get(mode)),
                        record.get(strategyName),
                        record.get(description),
                        record.get(archivedAt) == null ? "DRAFT" : "ARCHIVED",
                        record.get(latestValidation),
                        null,
                        record.get(archivedAt) == null,
                        record.get(updatedAt).toInstant(),
                        null));
    }

    private List<StrategyLibraryItem> findReleased(
            UUID ownerAccountId, OffsetDateTime snapshot, StrategyLibraryPosition after, int limit) {
        var bots = table(name("bot", "bots")).as("b");
        var backtests = table(name("backtest", "runs")).as("bt");
        Field<UUID> id = field(name("b", "id"), UUID.class);
        Field<UUID> owner = field(name("b", "owner_account_id"), UUID.class);
        Field<String> mode = field(name("b", "mode"), String.class);
        Field<String> botName = field(name("b", "name"), String.class);
        Field<String> lifecycle = field(name("b", "lifecycle_status"), String.class);
        Field<OffsetDateTime> updatedAt = field(name("b", "updated_at"), OffsetDateTime.class);
        Field<OffsetDateTime> deletedAt = field(name("b", "deleted_at"), OffsetDateTime.class);
        Field<UUID> backtestBotId = field(name("bt", "bot_id"), UUID.class);
        Field<String> backtestState = field(name("bt", "status"), String.class);
        Field<OffsetDateTime> queuedAt = field(name("bt", "queued_at"), OffsetDateTime.class);
        Field<String> latestBacktest = select(backtestState)
                .from(backtests)
                .where(backtestBotId.eq(id).and(queuedAt.le(snapshot)))
                .orderBy(queuedAt.desc())
                .limit(1)
                .asField("backtest_status");

        return dsl.select(id, mode, botName, lifecycle, latestBacktest, updatedAt)
                .from(bots)
                .where(owner.eq(ownerAccountId)
                        .and(deletedAt.isNull())
                        .and(updatedAt.le(snapshot))
                        .and(afterCondition(updatedAt, StrategyLibraryItemKind.RELEASED, id, after)))
                .orderBy(updatedAt.desc(), id.asc())
                .limit(limit)
                .fetch(record -> new StrategyLibraryItem(
                        record.get(id),
                        StrategyLibraryItemKind.RELEASED,
                        StrategyMode.valueOf(record.get(mode)),
                        record.get(botName),
                        null,
                        record.get(lifecycle),
                        null,
                        record.get(latestBacktest),
                        false,
                        record.get(updatedAt).toInstant(),
                        null));
    }

    private List<StrategyLibraryItem> findPackages(
            OffsetDateTime snapshot, StrategyLibraryPosition after, int limit) {
        var packages = table(name("strategy", "packages")).as("p");
        var versions = table(name("strategy", "package_versions")).as("pv");
        var newer = table(name("strategy", "package_versions")).as("npv");
        Field<UUID> packageId = field(name("p", "id"), UUID.class);
        Field<String> code = field(name("p", "code"), String.class);
        Field<String> status = field(name("p", "status"), String.class);
        Field<OffsetDateTime> packageRetiredAt = field(name("p", "retired_at"), OffsetDateTime.class);
        Field<UUID> id = field(name("pv", "id"), UUID.class);
        Field<UUID> versionPackageId = field(name("pv", "package_id"), UUID.class);
        Field<String> version = field(name("pv", "version"), String.class);
        Field<JSONB> names = field(name("pv", "name_i18n"), JSONB.class);
        Field<JSONB> descriptions = field(name("pv", "description_i18n"), JSONB.class);
        Field<OffsetDateTime> publishedAt = field(name("pv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> retiredAt = field(name("pv", "retired_at"), OffsetDateTime.class);
        Field<UUID> newerId = field(name("npv", "id"), UUID.class);
        Field<UUID> newerPackageId = field(name("npv", "package_id"), UUID.class);
        Field<OffsetDateTime> newerPublishedAt = field(name("npv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> newerRetiredAt = field(name("npv", "retired_at"), OffsetDateTime.class);
        Field<String> displayName = localized(names, code);
        Field<String> displayDescription = localized(descriptions, inline(null, String.class));

        Condition noNewerVersion = notExists(selectOne()
                .from(newer)
                .where(newerPackageId.eq(packageId)
                        .and(newerPublishedAt.le(snapshot))
                        .and(newerRetiredAt.isNull().or(newerRetiredAt.gt(snapshot)))
                        .and(newerPublishedAt.gt(publishedAt)
                                .or(newerPublishedAt.eq(publishedAt).and(newerId.gt(id))))));

        return dsl.select(id, displayName, displayDescription, status, version, publishedAt)
                .from(packages)
                .join(versions)
                .on(versionPackageId.eq(packageId))
                .where(status.eq("ACTIVE")
                        .and(packageRetiredAt.isNull().or(packageRetiredAt.gt(snapshot)))
                        .and(publishedAt.le(snapshot))
                        .and(retiredAt.isNull().or(retiredAt.gt(snapshot)))
                        .and(noNewerVersion)
                        .and(afterCondition(publishedAt, StrategyLibraryItemKind.PACKAGE, id, after)))
                .orderBy(publishedAt.desc(), id.asc())
                .limit(limit)
                .fetch(record -> new StrategyLibraryItem(
                        record.get(id),
                        StrategyLibraryItemKind.PACKAGE,
                        StrategyMode.BASIC,
                        record.get(displayName),
                        record.get(displayDescription),
                        record.get(status),
                        null,
                        null,
                        false,
                        record.get(publishedAt).toInstant(),
                        record.get(version)));
    }

    private List<StrategyLibraryItem> findTemplates(
            OffsetDateTime snapshot, StrategyLibraryPosition after, int limit) {
        var templates = table(name("strategy", "templates")).as("t");
        var versions = table(name("strategy", "template_versions")).as("tv");
        var newer = table(name("strategy", "template_versions")).as("ntv");
        Field<UUID> templateId = field(name("t", "id"), UUID.class);
        Field<String> code = field(name("t", "code"), String.class);
        Field<String> status = field(name("t", "status"), String.class);
        Field<OffsetDateTime> templateRetiredAt = field(name("t", "retired_at"), OffsetDateTime.class);
        Field<UUID> id = field(name("tv", "id"), UUID.class);
        Field<UUID> versionTemplateId = field(name("tv", "template_id"), UUID.class);
        Field<String> version = field(name("tv", "version"), String.class);
        Field<JSONB> names = field(name("tv", "name_i18n"), JSONB.class);
        Field<JSONB> descriptions = field(name("tv", "description_i18n"), JSONB.class);
        Field<OffsetDateTime> publishedAt = field(name("tv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> retiredAt = field(name("tv", "retired_at"), OffsetDateTime.class);
        Field<UUID> newerId = field(name("ntv", "id"), UUID.class);
        Field<UUID> newerTemplateId = field(name("ntv", "template_id"), UUID.class);
        Field<OffsetDateTime> newerPublishedAt = field(name("ntv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> newerRetiredAt = field(name("ntv", "retired_at"), OffsetDateTime.class);
        Field<String> displayName = localized(names, code);
        Field<String> displayDescription = localized(descriptions, inline(null, String.class));

        Condition noNewerVersion = notExists(selectOne()
                .from(newer)
                .where(newerTemplateId.eq(templateId)
                        .and(newerPublishedAt.le(snapshot))
                        .and(newerRetiredAt.isNull().or(newerRetiredAt.gt(snapshot)))
                        .and(newerPublishedAt.gt(publishedAt)
                                .or(newerPublishedAt.eq(publishedAt).and(newerId.gt(id))))));

        return dsl.select(id, displayName, displayDescription, status, version, publishedAt)
                .from(templates)
                .join(versions)
                .on(versionTemplateId.eq(templateId))
                .where(status.eq("ACTIVE")
                        .and(templateRetiredAt.isNull().or(templateRetiredAt.gt(snapshot)))
                        .and(publishedAt.le(snapshot))
                        .and(retiredAt.isNull().or(retiredAt.gt(snapshot)))
                        .and(noNewerVersion)
                        .and(afterCondition(publishedAt, StrategyLibraryItemKind.TEMPLATE, id, after)))
                .orderBy(publishedAt.desc(), id.asc())
                .limit(limit)
                .fetch(record -> new StrategyLibraryItem(
                        record.get(id),
                        StrategyLibraryItemKind.TEMPLATE,
                        StrategyMode.PRO,
                        record.get(displayName),
                        record.get(displayDescription),
                        record.get(status),
                        null,
                        null,
                        false,
                        record.get(publishedAt).toInstant(),
                        record.get(version)));
    }

    private static Field<String> localized(Field<JSONB> i18n, Field<String> fallback) {
        return coalesce(
                function("jsonb_extract_path_text", String.class, i18n, inline("ko")),
                function("jsonb_extract_path_text", String.class, i18n, inline("en")),
                fallback);
    }

    private static Condition afterCondition(
            Field<OffsetDateTime> sortTime,
            StrategyLibraryItemKind kind,
            Field<UUID> id,
            StrategyLibraryPosition after) {
        if (after == null) {
            return noCondition();
        }
        OffsetDateTime cursorTime = after.sortTime().atOffset(ZoneOffset.UTC);
        int kindComparison = kind.compareTo(after.kind());
        Condition atSameTime = kindComparison > 0
                ? noCondition()
                : kindComparison == 0 ? id.gt(after.id()) : org.jooq.impl.DSL.falseCondition();
        return sortTime.lt(cursorTime).or(sortTime.eq(cursorTime).and(atSameTime));
    }
}
