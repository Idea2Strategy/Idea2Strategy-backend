package com.idea2strategy.backend.persistence.strategy;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.strategy.StrategyValidationRunQueryPort;
import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogItem;
import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogQueryPort;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class StrategyValidationRunJooqQueryAdapter
        implements StrategyValidationRunQueryPort, OwnedStrategyValidationCatalogQueryPort {
    private final DSLContext dsl;

    public StrategyValidationRunJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<StrategyValidationRun> findOwnedById(UUID validationRunId, UUID ownerAccountId) {
        var runs = table(name("strategy", "validation_runs")).as("run");
        var strategies = table(name("strategy", "strategies")).as("strategy");
        var id = field(name("run", "id"), UUID.class);
        var strategyId = field(name("run", "strategy_id"), UUID.class);
        var strategyRowId = field(name("strategy", "id"), UUID.class);
        var strategyOwner = field(name("strategy", "owner_account_id"), UUID.class);
        var requestedBy = field(name("run", "requested_by_account_id"), UUID.class);
        var delegatedAuthorizationId = field(name("run", "delegated_authorization_id"), UUID.class);
        var requestedEditSequence = field(name("run", "requested_edit_sequence"), Long.class);
        var semanticHash = field(name("run", "semantic_hash"), String.class);
        var catalogVersionId = field(name("run", "element_catalog_version_id"), UUID.class);
        var status = field(name("run", "status"), String.class);
        var resultDocument = field(name("run", "result_document"), JSONB.class);
        var requestedAt = field(name("run", "requested_at"), OffsetDateTime.class);
        var completedAt = field(name("run", "completed_at"), OffsetDateTime.class);

        return dsl.select(
                        id,
                        strategyId,
                        requestedBy,
                        delegatedAuthorizationId,
                        requestedEditSequence,
                        semanticHash,
                        catalogVersionId,
                        status,
                        resultDocument,
                        requestedAt,
                        completedAt)
                .from(runs)
                .join(strategies)
                .on(strategyId.eq(strategyRowId))
                .where(id.eq(validationRunId).and(strategyOwner.eq(ownerAccountId)))
                .fetchOptional(record -> new StrategyValidationRun(
                        record.get(id),
                        record.get(strategyId),
                        record.get(requestedBy),
                        record.get(delegatedAuthorizationId),
                        record.get(requestedEditSequence),
                        record.get(semanticHash),
                        record.get(catalogVersionId),
                        StrategyValidationStatus.valueOf(record.get(status)),
                        StrategyValidationResultJson.read(record.get(resultDocument).data()),
                        record.get(requestedAt).toInstant(),
                        record.get(completedAt) == null ? null : record.get(completedAt).toInstant()));
    }

    @Override
    public List<OwnedStrategyValidationCatalogItem> findCurrentValidOwnedBy(UUID ownerAccountId) {
        var runs = table(name("strategy", "validation_runs")).as("run");
        var strategies = table(name("strategy", "strategies")).as("strategy");
        var documents = table(name("strategy", "strategy_documents")).as("document");
        var id = field(name("run", "id"), UUID.class);
        var strategyId = field(name("run", "strategy_id"), UUID.class);
        var strategyRowId = field(name("strategy", "id"), UUID.class);
        var strategyName = field(name("strategy", "name"), String.class);
        var strategyOwner = field(name("strategy", "owner_account_id"), UUID.class);
        var documentStrategyId = field(name("document", "strategy_id"), UUID.class);
        var documentSemanticHash = field(name("document", "semantic_hash"), String.class);
        var documentEditSequence = field(name("document", "edit_sequence"), Long.class);
        var requestedEditSequence = field(name("run", "requested_edit_sequence"), Long.class);
        var semanticHash = field(name("run", "semantic_hash"), String.class);
        var catalogVersionId = field(name("run", "element_catalog_version_id"), UUID.class);
        var status = field(name("run", "status"), String.class);
        var completedAt = field(name("run", "completed_at"), OffsetDateTime.class);

        return dsl.select(
                        id,
                        strategyId,
                        strategyName,
                        requestedEditSequence,
                        semanticHash,
                        catalogVersionId,
                        completedAt)
                .from(runs)
                .join(strategies)
                .on(strategyId.eq(strategyRowId))
                .join(documents)
                .on(strategyId.eq(documentStrategyId))
                .where(strategyOwner.eq(ownerAccountId))
                .and(status.eq(StrategyValidationStatus.VALID.name()))
                .and(semanticHash.eq(documentSemanticHash))
                .and(requestedEditSequence.eq(documentEditSequence))
                .orderBy(completedAt.desc(), id.desc())
                .fetch(record -> new OwnedStrategyValidationCatalogItem(
                        record.get(id),
                        record.get(strategyId),
                        record.get(strategyName),
                        record.get(requestedEditSequence),
                        record.get(semanticHash),
                        record.get(catalogVersionId),
                        record.get(completedAt).toInstant()));
    }
}
