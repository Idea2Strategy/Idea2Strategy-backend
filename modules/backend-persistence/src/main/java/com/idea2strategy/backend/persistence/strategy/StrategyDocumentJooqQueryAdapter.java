package com.idea2strategy.backend.persistence.strategy;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryPort;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class StrategyDocumentJooqQueryAdapter implements StrategyDocumentQueryPort {
    private final DSLContext dsl;

    public StrategyDocumentJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<StrategyDocument> findOwnedByStrategyId(UUID strategyId, UUID ownerAccountId) {
        var documents = table(name("strategy", "strategy_documents")).as("document");
        var strategies = table(name("strategy", "strategies")).as("strategy");
        var documentStrategyId = field(name("document", "strategy_id"), UUID.class);
        var strategyIdField = field(name("strategy", "id"), UUID.class);
        var strategyOwner = field(name("strategy", "owner_account_id"), UUID.class);
        var semanticDocument = field(name("document", "semantic_document"), JSONB.class);
        var presentationDocument = field(name("document", "presentation_document"), JSONB.class);
        var semanticSchemaVersion = field(name("document", "semantic_schema_version"), String.class);
        var presentationSchemaVersion = field(name("document", "presentation_schema_version"), String.class);
        var semanticHash = field(name("document", "semantic_hash"), String.class);
        var presentationHash = field(name("document", "presentation_hash"), String.class);
        var editSequence = field(name("document", "edit_sequence"), Long.class);
        var createdAt = field(name("document", "created_at"), OffsetDateTime.class);
        var updatedAt = field(name("document", "updated_at"), OffsetDateTime.class);

        return dsl.select(
                        documentStrategyId,
                        semanticDocument,
                        presentationDocument,
                        semanticSchemaVersion,
                        presentationSchemaVersion,
                        semanticHash,
                        presentationHash,
                        editSequence,
                        createdAt,
                        updatedAt)
                .from(documents)
                .join(strategies)
                .on(documentStrategyId.eq(strategyIdField))
                .where(documentStrategyId.eq(strategyId).and(strategyOwner.eq(ownerAccountId)))
                .fetchOptional(record -> new StrategyDocument(
                        record.get(documentStrategyId),
                        StrategyDocumentJson.canonicalize(record.get(semanticDocument).data()),
                        StrategyDocumentJson.canonicalize(record.get(presentationDocument).data()),
                        record.get(semanticSchemaVersion),
                        record.get(presentationSchemaVersion),
                        record.get(semanticHash),
                        record.get(presentationHash),
                        record.get(editSequence),
                        record.get(createdAt).toInstant(),
                        record.get(updatedAt).toInstant()));
    }
}
