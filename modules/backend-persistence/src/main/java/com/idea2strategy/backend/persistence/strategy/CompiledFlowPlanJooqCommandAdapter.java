package com.idea2strategy.backend.persistence.strategy;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.strategy.CompiledFlowPlanCommandPort;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CompiledFlowPlanJooqCommandAdapter implements CompiledFlowPlanCommandPort {
    private final DSLContext dsl;

    public CompiledFlowPlanJooqCommandAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public CompiledFlowPlan saveOrFind(CompiledFlowPlan candidate) {
        var plans = table(name("strategy", "compiled_flow_plans"));
        var id = field(name("id"), UUID.class);
        var catalogId = field(name("element_catalog_version_id"), UUID.class);
        var semanticHash = field(name("semantic_hash"), String.class);
        var compilerVersion = field(name("compiler_version"), String.class);
        var featureHash = field(name("required_feature_set_hash"), String.class);
        var planDocument = field(name("plan_document"), JSONB.class);
        var planHash = field(name("plan_hash"), String.class);
        var createdAt = field(name("created_at"), OffsetDateTime.class);

        dsl.insertInto(plans)
                .columns(id, catalogId, semanticHash, compilerVersion, featureHash, planDocument, planHash, createdAt)
                .values(
                        candidate.id(),
                        candidate.elementCatalogVersionId(),
                        candidate.semanticHash(),
                        candidate.compilerVersion(),
                        candidate.requiredFeatureSetHash(),
                        JSONB.valueOf(candidate.planDocument()),
                        candidate.planHash(),
                        candidate.createdAt().atOffset(ZoneOffset.UTC))
                .onConflict(catalogId, semanticHash, compilerVersion)
                .doNothing()
                .execute();

        return dsl.select(id, catalogId, semanticHash, compilerVersion, featureHash, planDocument, planHash, createdAt)
                .from(plans)
                .where(catalogId.eq(candidate.elementCatalogVersionId())
                        .and(semanticHash.eq(candidate.semanticHash()))
                        .and(compilerVersion.eq(candidate.compilerVersion())))
                .fetchOptional(record -> new CompiledFlowPlan(
                        record.get(id),
                        record.get(catalogId),
                        record.get(semanticHash),
                        record.get(compilerVersion),
                        record.get(featureHash),
                        StrategyDocumentJson.canonicalize(record.get(planDocument).data()),
                        record.get(planHash),
                        record.get(createdAt).toInstant()))
                .orElseThrow(() -> new IllegalStateException("Compiled flow plan was not persisted"));
    }
}
