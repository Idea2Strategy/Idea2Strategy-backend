package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandPort;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BasicStrategyDraftJpaCommandAdapter implements BasicStrategyDraftCommandPort {
    private final StrategySpringDataRepository strategyRepository;
    private final StrategyDocumentSpringDataRepository documentRepository;
    private final JdbcTemplate jdbcTemplate;

    public BasicStrategyDraftJpaCommandAdapter(
            StrategySpringDataRepository strategyRepository,
            StrategyDocumentSpringDataRepository documentRepository,
            JdbcTemplate jdbcTemplate) {
        this.strategyRepository = strategyRepository;
        this.documentRepository = documentRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void create(Strategy strategy, StrategyDocument document) {
        strategyRepository.saveAndFlush(StrategyJpaEntity.from(strategy));
        documentRepository.saveAndFlush(StrategyDocumentJpaEntity.from(document));
    }

    @Override
    @Transactional
    public boolean replaceDocument(StrategyDocument document, long expectedEditSequence) {
        int updated = jdbcTemplate.update(
                """
                update strategy.strategy_documents
                   set semantic_document = cast(? as jsonb),
                       presentation_document = cast(? as jsonb),
                       semantic_schema_version = ?,
                       presentation_schema_version = ?,
                       semantic_hash = ?,
                       presentation_hash = ?,
                       edit_sequence = ?,
                       updated_at = ?
                 where strategy_id = ?
                   and edit_sequence = ?
                """,
                document.semanticDocument(),
                document.presentationDocument(),
                document.semanticSchemaVersion(),
                document.presentationSchemaVersion(),
                document.semanticHash(),
                document.presentationHash(),
                document.editSequence(),
                document.updatedAt().atOffset(ZoneOffset.UTC),
                document.strategyId(),
                expectedEditSequence);
        return updated == 1;
    }
}
