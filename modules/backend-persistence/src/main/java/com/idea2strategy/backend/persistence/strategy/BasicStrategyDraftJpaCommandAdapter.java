package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandPort;
import com.idea2strategy.backend.application.strategy.StrategyDraftReplaceResult;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
    public StrategyDraftReplaceResult replaceDocument(
            StrategyDocument document,
            long expectedEditSequence,
            UUID sessionId,
            String leaseTokenDigest,
            Instant now) {
        int updated = jdbcTemplate.update(
                """
                update strategy.strategy_documents as document
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
                   and exists (
                       select 1
                         from strategy.strategy_edit_leases lease
                        where lease.strategy_id = document.strategy_id
                          and lease.session_id = ?
                          and lease.lease_token_digest = ?
                          and lease.expires_at > ?
                   )
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
                expectedEditSequence,
                sessionId,
                leaseTokenDigest,
                now.atOffset(ZoneOffset.UTC));
        if (updated == 1) {
            return StrategyDraftReplaceResult.UPDATED;
        }
        Long currentEditSequence = jdbcTemplate.queryForObject(
                "select edit_sequence from strategy.strategy_documents where strategy_id = ?",
                Long.class,
                document.strategyId());
        return currentEditSequence != null && currentEditSequence == expectedEditSequence
                ? StrategyDraftReplaceResult.INVALID_LEASE
                : StrategyDraftReplaceResult.STALE_EDIT_SEQUENCE;
    }
}
