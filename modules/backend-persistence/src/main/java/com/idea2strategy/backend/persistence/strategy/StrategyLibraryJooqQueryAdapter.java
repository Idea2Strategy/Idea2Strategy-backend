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
        var documents = table(name("strategy", "strategy_documents")).as("d");
        var validations = table(name("strategy", "validation_runs")).as("v");
        Field<UUID> id = field(name("s", "id"), UUID.class);
        Field<UUID> owner = field(name("s", "owner_account_id"), UUID.class);
        Field<String> mode = field(name("s", "mode"), String.class);
        Field<String> strategyName = field(name("s", "name"), String.class);
        Field<String> description = field(name("s", "description"), String.class);
        Field<OffsetDateTime> updatedAt = field(name("s", "updated_at"), OffsetDateTime.class);
        Field<OffsetDateTime> archivedAt = field(name("s", "archived_at"), OffsetDateTime.class);
        Field<OffsetDateTime> deletedAt = field(name("s", "deleted_at"), OffsetDateTime.class);
        Field<UUID> documentStrategyId = field(name("d", "strategy_id"), UUID.class);
        Field<JSONB> semanticDocument = field(name("d", "semantic_document"), JSONB.class);
        Field<Long> documentEditSequence = field(name("d", "edit_sequence"), Long.class);
        Field<String> documentSemanticHash = field(name("d", "semantic_hash"), String.class);
        Field<Integer> blockCount = blockCount(semanticDocument);
        Field<String[]> symbols = symbols(semanticDocument, snapshot);
        Field<UUID> validationStrategyId = field(name("v", "strategy_id"), UUID.class);
        Field<String> validationState = field(name("v", "status"), String.class);
        Field<Long> validationEditSequence = field(name("v", "requested_edit_sequence"), Long.class);
        Field<String> validationSemanticHash = field(name("v", "semantic_hash"), String.class);
        Field<OffsetDateTime> validationRequestedAt = field(name("v", "requested_at"), OffsetDateTime.class);
        Field<String> latestValidation = select(validationState)
                .from(validations)
                .where(validationStrategyId.eq(id)
                        .and(validationRequestedAt.le(snapshot))
                        .and(validationEditSequence.eq(documentEditSequence))
                        .and(validationSemanticHash.eq(documentSemanticHash)))
                .orderBy(validationRequestedAt.desc())
                .limit(1)
                .asField();
        Field<String> normalizedValidation = when(latestValidation.eq("PASSED"), inline("VALID"))
                .otherwise(latestValidation)
                .as("validation_status");
        Field<String> draftStatus = when(latestValidation.in("VALID", "PASSED"), inline("READY"))
                .otherwise(inline("INCOMPLETE"));
        Field<String> libraryStatus = when(archivedAt.isNull(), draftStatus)
                .otherwise(inline("ARCHIVED"))
                .as("library_status");

        return dsl.select(
                        id,
                        mode,
                        strategyName,
                        description,
                        libraryStatus,
                        normalizedValidation,
                        updatedAt,
                        archivedAt,
                        blockCount,
                        symbols)
                .from(strategies)
                .leftJoin(documents)
                .on(documentStrategyId.eq(id))
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
                        record.get(libraryStatus),
                        record.get(normalizedValidation),
                        null,
                        record.get(archivedAt) == null,
                        record.get(updatedAt).toInstant(),
                        null,
                        record.get(blockCount),
                        symbolList(record.get(symbols))));
    }

    private List<StrategyLibraryItem> findReleased(
            UUID ownerAccountId, OffsetDateTime snapshot, StrategyLibraryPosition after, int limit) {
        var bots = table(name("bot", "bots")).as("b");
        var snapshots = table(name("bot", "launch_snapshots")).as("ls");
        var backtests = table(name("backtest", "runs")).as("bt");
        Field<UUID> id = field(name("b", "id"), UUID.class);
        Field<UUID> owner = field(name("b", "owner_account_id"), UUID.class);
        Field<String> mode = field(name("b", "mode"), String.class);
        Field<String> botName = field(name("b", "name"), String.class);
        Field<String> lifecycle = field(name("b", "lifecycle_status"), String.class);
        Field<OffsetDateTime> updatedAt = field(name("b", "updated_at"), OffsetDateTime.class);
        Field<OffsetDateTime> deletedAt = field(name("b", "deleted_at"), OffsetDateTime.class);
        Field<UUID> snapshotBotId = field(name("ls", "bot_id"), UUID.class);
        Field<JSONB> semanticSnapshot = field(name("ls", "semantic_snapshot"), JSONB.class);
        Field<Integer> blockCount = blockCount(semanticSnapshot);
        Field<String[]> symbols = symbols(semanticSnapshot, snapshot);
        Field<UUID> backtestBotId = field(name("bt", "bot_id"), UUID.class);
        Field<String> backtestState = field(name("bt", "status"), String.class);
        Field<OffsetDateTime> queuedAt = field(name("bt", "queued_at"), OffsetDateTime.class);
        Field<String> latestBacktest = select(backtestState)
                .from(backtests)
                .where(backtestBotId.eq(id).and(queuedAt.le(snapshot)))
                .orderBy(queuedAt.desc())
                .limit(1)
                .asField("backtest_status");

        return dsl.select(id, mode, botName, lifecycle, latestBacktest, updatedAt, blockCount, symbols)
                .from(bots)
                .leftJoin(snapshots)
                .on(snapshotBotId.eq(id))
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
                        null,
                        record.get(blockCount),
                        symbolList(record.get(symbols))));
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
        Field<JSONB> flowDocument = field(name("pv", "flow_document"), JSONB.class);
        Field<OffsetDateTime> publishedAt = field(name("pv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> retiredAt = field(name("pv", "retired_at"), OffsetDateTime.class);
        Field<UUID> newerId = field(name("npv", "id"), UUID.class);
        Field<UUID> newerPackageId = field(name("npv", "package_id"), UUID.class);
        Field<OffsetDateTime> newerPublishedAt = field(name("npv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> newerRetiredAt = field(name("npv", "retired_at"), OffsetDateTime.class);
        Field<String> displayName = localized(names, code);
        Field<String> displayDescription = localized(descriptions, inline(null, String.class));
        Field<Integer> blockCount = blockCount(flowDocument);
        Field<String[]> symbols = symbols(flowDocument, snapshot);

        Condition noNewerVersion = notExists(selectOne()
                .from(newer)
                .where(newerPackageId.eq(packageId)
                        .and(newerPublishedAt.le(snapshot))
                        .and(newerRetiredAt.isNull().or(newerRetiredAt.gt(snapshot)))
                        .and(newerPublishedAt.gt(publishedAt)
                                .or(newerPublishedAt.eq(publishedAt).and(newerId.gt(id))))));

        return dsl.select(id, displayName, displayDescription, status, version, publishedAt, blockCount, symbols)
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
                        record.get(version),
                        record.get(blockCount),
                        symbolList(record.get(symbols))));
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
        Field<JSONB> semanticSkeleton = field(name("tv", "semantic_skeleton"), JSONB.class);
        Field<OffsetDateTime> publishedAt = field(name("tv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> retiredAt = field(name("tv", "retired_at"), OffsetDateTime.class);
        Field<UUID> newerId = field(name("ntv", "id"), UUID.class);
        Field<UUID> newerTemplateId = field(name("ntv", "template_id"), UUID.class);
        Field<OffsetDateTime> newerPublishedAt = field(name("ntv", "published_at"), OffsetDateTime.class);
        Field<OffsetDateTime> newerRetiredAt = field(name("ntv", "retired_at"), OffsetDateTime.class);
        Field<String> displayName = localized(names, code);
        Field<String> displayDescription = localized(descriptions, inline(null, String.class));
        Field<Integer> blockCount = blockCount(semanticSkeleton);
        Field<String[]> symbols = symbols(semanticSkeleton, snapshot);

        Condition noNewerVersion = notExists(selectOne()
                .from(newer)
                .where(newerTemplateId.eq(templateId)
                        .and(newerPublishedAt.le(snapshot))
                        .and(newerRetiredAt.isNull().or(newerRetiredAt.gt(snapshot)))
                        .and(newerPublishedAt.gt(publishedAt)
                                .or(newerPublishedAt.eq(publishedAt).and(newerId.gt(id))))));

        return dsl.select(id, displayName, displayDescription, status, version, publishedAt, blockCount, symbols)
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
                        record.get(version),
                        record.get(blockCount),
                        symbolList(record.get(symbols))));
    }

    private static Field<Integer> blockCount(Field<JSONB> document) {
        return field(
                "(select coalesce(sum(jsonb_array_length(case when jsonb_typeof(group_node -> 'blocks') = 'array' then group_node -> 'blocks' else '[]'::jsonb end)), 0)::int "
                        + "from jsonb_array_elements(case when jsonb_typeof({0} -> 'groups') = 'array' then {0} -> 'groups' else '[]'::jsonb end) group_node)",
                Integer.class,
                document);
    }

    private static Field<String[]> symbols(Field<JSONB> document, OffsetDateTime snapshot) {
        return field(
                "(select coalesce(array_agg(distinct active_symbol.symbol::text order by active_symbol.symbol::text), array[]::text[]) "
                        + "from jsonb_array_elements(case when jsonb_typeof({0} -> 'groups') = 'array' then {0} -> 'groups' else '[]'::jsonb end) group_node "
                        + "cross join lateral jsonb_array_elements_text(case when jsonb_typeof(group_node -> 'instrumentIds') = 'array' then group_node -> 'instrumentIds' else '[]'::jsonb end) requested(value) "
                        + "join market_data.instrument_symbols active_symbol on active_symbol.instrument_id = "
                        + "case when requested.value ~ '^[0-9a-fA-F-]{36}$' then requested.value::uuid else null end "
                        + "and active_symbol.effective_from <= {1} "
                        + "and (active_symbol.effective_to is null or active_symbol.effective_to > {1}))",
                String[].class,
                document,
                inline(snapshot));
    }

    private static List<String> symbolList(String[] values) {
        return values == null ? List.of() : List.of(values);
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
