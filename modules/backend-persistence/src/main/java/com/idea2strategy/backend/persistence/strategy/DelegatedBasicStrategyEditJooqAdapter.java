package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.DelegatedBasicEditCommandPort;
import com.idea2strategy.backend.application.strategy.DelegatedBasicEditRejectedException;
import com.idea2strategy.backend.application.strategy.DelegatedBasicEditReplaceResult;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyAuthorizationPort;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyEditor;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScope;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DelegatedBasicStrategyEditJooqAdapter
        implements DelegatedStrategyAuthorizationPort, DelegatedBasicEditCommandPort {
    private final DSLContext dsl;

    public DelegatedBasicStrategyEditJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void requireAuthorized(
            DelegatedStrategyEditor editor,
            UUID strategyId,
            DelegatedStrategyScope scope,
            Instant at) {
        if (!isAuthorized(editor, strategyId, scope, at)) {
            throw new DelegatedBasicEditRejectedException(
                    "Delegated authorization is not active for " + scope.name());
        }
    }

    @Override
    @Transactional
    public DelegatedBasicEditReplaceResult replace(
            StrategyDocument document,
            long expectedEditSequence,
            DelegatedStrategyEditor editor,
            Instant at) {
        var current = dsl.fetchOne(
                "select d.edit_sequence, d.semantic_hash "
                        + "from strategy.strategy_documents d "
                        + "join strategy.strategies s on s.id = d.strategy_id "
                        + "where d.strategy_id = ? and s.owner_account_id = ? for update of d",
                document.strategyId(), editor.accountId());
        if (current == null) {
            return DelegatedBasicEditReplaceResult.UNAUTHORIZED;
        }
        Long currentSequence = current.get("edit_sequence", Long.class);
        if (currentSequence == null || currentSequence != expectedEditSequence) {
            return DelegatedBasicEditReplaceResult.STALE_EDIT_SEQUENCE;
        }
        if (!isAuthorized(editor, document.strategyId(), DelegatedStrategyScope.STRATEGY_EDIT, at)) {
            return DelegatedBasicEditReplaceResult.UNAUTHORIZED;
        }

        int updated = dsl.execute(
                "update strategy.strategy_documents set "
                        + "semantic_document = ?::jsonb, presentation_document = ?::jsonb, "
                        + "semantic_schema_version = ?, presentation_schema_version = ?, "
                        + "semantic_hash = ?, presentation_hash = ?, edit_sequence = ?, updated_at = ?::timestamptz "
                        + "where strategy_id = ? and edit_sequence = ?",
                document.semanticDocument(), document.presentationDocument(), document.semanticSchemaVersion(),
                document.presentationSchemaVersion(), document.semanticHash(), document.presentationHash(),
                document.editSequence(), document.updatedAt().atOffset(ZoneOffset.UTC),
                document.strategyId(), expectedEditSequence);
        if (updated != 1) {
            return DelegatedBasicEditReplaceResult.STALE_EDIT_SEQUENCE;
        }

        String beforeHash = current.get("semantic_hash", String.class);
        String material = editor.authorizationId() + "\n" + document.strategyId() + "\n"
                + expectedEditSequence + "\n" + document.semanticHash();
        String idempotencyKey = "sha256:" + StrategyDocumentJson.sha256(material);
        dsl.execute(
                "insert into operations.audit_events "
                        + "(id, actor_type, actor_id, delegated_authorization_id, action_type, target_domain, "
                        + "target_id, reason_code, correlation_id, idempotency_key, before_hash, after_hash, "
                        + "occurred_at) values (?, 'DELEGATED_AUTHORIZATION', ?, ?, 'EXTERNAL_BASIC_STRATEGY_EDIT', "
                        + "'strategy', ?, 'DELEGATED_BASIC_EDIT', ?, ?, ?, ?, ?::timestamptz)",
                derivedId(material, "audit"), editor.authorizationId(), editor.authorizationId(),
                document.strategyId(), derivedId(material, "correlation"), idempotencyKey,
                beforeHash, document.semanticHash(), at.atOffset(ZoneOffset.UTC));
        return DelegatedBasicEditReplaceResult.UPDATED;
    }

    private boolean isAuthorized(
            DelegatedStrategyEditor editor,
            UUID strategyId,
            DelegatedStrategyScope scope,
            Instant at) {
        return dsl.fetchOne(
                "select 1 from identity.delegated_authorizations a "
                        + "join identity.delegated_credentials c on c.authorization_id = a.id "
                        + "join identity.delegated_authorization_scopes sc on sc.authorization_id = a.id "
                        + "join identity.accounts account on account.id = a.account_id "
                        + "join identity.account_security_states security on security.account_id = account.id "
                        + "join strategy.strategies strategy on strategy.owner_account_id = account.id "
                        + "left join identity.delegated_authorization_strategy_targets target "
                        + "on target.authorization_id = a.id and target.strategy_id = strategy.id "
                        + "left join identity.delegated_strategy_derivations derivation "
                        + "on derivation.authorization_id = a.id and derivation.result_strategy_id = strategy.id "
                        + "where a.id = ? and c.id = ? and a.account_id = ? and strategy.id = ? "
                        + "and sc.scope_code = ?::identity.delegated_scope "
                        + "and a.status = 'ACTIVE' and a.revoked_at is null "
                        + "and (a.expiry_mode <> 'AT_TIME' or a.expires_at > ?::timestamptz) "
                        + "and a.auth_epoch_at_grant = security.auth_epoch "
                        + "and account.lifecycle_status = 'ACTIVE' "
                        + "and not exists (select 1 from identity.account_sanctions sanction "
                        + "where sanction.account_id = account.id and sanction.status = 'ACTIVE') "
                        + "and c.credential_type = 'ACCESS_TOKEN' and c.revoked_at is null "
                        + "and c.expires_at > ?::timestamptz "
                        + "and strategy.mode = 'BASIC' and strategy.archived_at is null and strategy.deleted_at is null "
                        + "and ((target.authorization_id is not null "
                        + "and target.owner_account_id_at_grant = strategy.owner_account_id "
                        + "and target.strategy_access_epoch_at_grant = strategy.delegated_access_epoch) "
                        + "or (derivation.authorization_id is not null "
                        + "and derivation.owner_account_id_at_creation = strategy.owner_account_id "
                        + "and derivation.strategy_access_epoch_at_creation = strategy.delegated_access_epoch))",
                editor.authorizationId(), editor.credentialId(), editor.accountId(), strategyId, scope.name(),
                at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC)) != null;
    }

    private static UUID derivedId(String material, String purpose) {
        return UUID.nameUUIDFromBytes((purpose + ":" + material).getBytes(StandardCharsets.UTF_8));
    }
}
