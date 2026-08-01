package com.idea2strategy.backend.persistence.competition;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogQueryPort;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class ScoringTemplateCatalogJooqQueryAdapter implements ScoringTemplateCatalogQueryPort {
    private final DSLContext dsl;

    public ScoringTemplateCatalogJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ScoringTemplateCatalogRecord> findSelectableAt(Instant at) {
        var catalog = new CatalogFields();
        var observedAt = at.atOffset(ZoneOffset.UTC);

        return dsl.select(catalog.fields())
                .from(catalog.table)
                .where(catalog.publishedAt.le(observedAt))
                .and(catalog.retiredAt.isNull().or(catalog.retiredAt.gt(observedAt)))
                .orderBy(catalog.templateCode, catalog.version)
                .fetch(record -> catalog.toRecord(record));
    }

    @Override
    public Optional<ScoringTemplateCatalogRecord> findSelectableById(UUID id, Instant at) {
        var catalog = new CatalogFields();
        var observedAt = at.atOffset(ZoneOffset.UTC);

        return dsl.select(catalog.fields())
                .from(catalog.table)
                .where(catalog.id.eq(id))
                .and(catalog.publishedAt.le(observedAt))
                .and(catalog.retiredAt.isNull().or(catalog.retiredAt.gt(observedAt)))
                .fetchOptional(record -> catalog.toRecord(record));
    }

    private static final class CatalogFields {
        private final org.jooq.Table<?> table = table(name("competition", "scoring_template_versions")).as("stv");
        private final org.jooq.Field<UUID> id = field(name("stv", "id"), UUID.class);
        private final org.jooq.Field<String> templateCode = field(name("stv", "template_code"), String.class);
        private final org.jooq.Field<String> version = field(name("stv", "version"), String.class);
        private final org.jooq.Field<JSONB> rulesDocument = field(name("stv", "rules_document"), JSONB.class);
        private final org.jooq.Field<String> rulesHash = field(name("stv", "rules_hash"), String.class);
        private final org.jooq.Field<OffsetDateTime> publishedAt =
                field(name("stv", "published_at"), OffsetDateTime.class);
        private final org.jooq.Field<OffsetDateTime> retiredAt = field(name("stv", "retired_at"), OffsetDateTime.class);

        private org.jooq.Field<?>[] fields() {
            return new org.jooq.Field<?>[] {
                id, templateCode, version, rulesDocument, rulesHash, publishedAt, retiredAt
            };
        }

        private ScoringTemplateCatalogRecord toRecord(Record record) {
            var retired = record.get(retiredAt);
            return new ScoringTemplateCatalogRecord(
                    record.get(id),
                    record.get(templateCode),
                    record.get(version),
                    record.get(rulesDocument).data(),
                    record.get(rulesHash),
                    record.get(publishedAt).toInstant(),
                    retired == null ? null : retired.toInstant());
        }
    }
}
