package com.idea2strategy.backend.persistence.strategy;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.strategy.BasicStructureCandidate;
import com.idea2strategy.backend.application.strategy.BasicStructureCatalogQueryPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class BasicStructureCatalogJooqQueryAdapter implements BasicStructureCatalogQueryPort {
    private final DSLContext dsl;

    public BasicStructureCatalogJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<BasicStructureCandidate> findActivePublishedByCatalogId(UUID catalogId, Instant publishedAt) {
        var packages = table(name("strategy", "packages")).as("package");
        var versions = table(name("strategy", "package_versions")).as("version");
        var packageId = field(name("package", "id"), UUID.class);
        var versionPackageId = field(name("version", "package_id"), UUID.class);
        var id = field(name("version", "id"), UUID.class);
        var code = field(name("package", "code"), String.class);
        var packageStatus = field(name("package", "status"), String.class);
        var packageRetiredAt = field(name("package", "retired_at"), OffsetDateTime.class);
        var version = field(name("version", "version"), String.class);
        var elementCatalogId = field(name("version", "element_catalog_version_id"), UUID.class);
        var nameDocument = field(name("version", "name_i18n"), JSONB.class);
        var descriptionDocument = field(name("version", "description_i18n"), JSONB.class);
        var flowDocument = field(name("version", "flow_document"), JSONB.class);
        var contentHash = field(name("version", "content_hash"), String.class);
        var versionPublishedAt = field(name("version", "published_at"), OffsetDateTime.class);
        var versionRetiredAt = field(name("version", "retired_at"), OffsetDateTime.class);
        OffsetDateTime observedAt = publishedAt.atOffset(ZoneOffset.UTC);

        return dsl.select(
                        id,
                        packageId,
                        code,
                        version,
                        elementCatalogId,
                        nameDocument,
                        descriptionDocument,
                        flowDocument,
                        contentHash,
                        versionPublishedAt)
                .from(versions)
                .join(packages)
                .on(versionPackageId.eq(packageId))
                .where(packageStatus.eq("ACTIVE")
                        .and(packageRetiredAt.isNull().or(packageRetiredAt.gt(observedAt)))
                        .and(elementCatalogId.eq(catalogId))
                        .and(versionPublishedAt.le(observedAt))
                        .and(versionRetiredAt.isNull().or(versionRetiredAt.gt(observedAt))))
                .orderBy(code, version)
                .fetch(record -> new BasicStructureCandidate(
                        record.get(id),
                        record.get(packageId),
                        record.get(code),
                        record.get(version),
                        record.get(elementCatalogId),
                        record.get(nameDocument).data(),
                        record.get(descriptionDocument).data(),
                        record.get(flowDocument).data(),
                        record.get(contentHash),
                        record.get(versionPublishedAt).toInstant()));
    }
}
