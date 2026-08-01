package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "strategy_documents", schema = "strategy")
public class StrategyDocumentJpaEntity {
    @Id
    @Column(name = "strategy_id")
    private UUID strategyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "semantic_document", nullable = false, columnDefinition = "jsonb")
    private String semanticDocument;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "presentation_document", nullable = false, columnDefinition = "jsonb")
    private String presentationDocument;

    @Column(name = "semantic_schema_version", nullable = false, length = 40)
    private String semanticSchemaVersion;

    @Column(name = "presentation_schema_version", nullable = false, length = 40)
    private String presentationSchemaVersion;

    @Column(name = "semantic_hash", nullable = false, length = 128)
    private String semanticHash;

    @Column(name = "presentation_hash", nullable = false, length = 128)
    private String presentationHash;

    @Column(name = "edit_sequence", nullable = false)
    private long editSequence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StrategyDocumentJpaEntity() {}

    private StrategyDocumentJpaEntity(StrategyDocument document) {
        this.strategyId = document.strategyId();
        this.semanticDocument = document.semanticDocument();
        this.presentationDocument = document.presentationDocument();
        this.semanticSchemaVersion = document.semanticSchemaVersion();
        this.presentationSchemaVersion = document.presentationSchemaVersion();
        this.semanticHash = document.semanticHash();
        this.presentationHash = document.presentationHash();
        this.editSequence = document.editSequence();
        this.createdAt = document.createdAt();
        this.updatedAt = document.updatedAt();
    }

    static StrategyDocumentJpaEntity from(StrategyDocument document) {
        return new StrategyDocumentJpaEntity(document);
    }
}
