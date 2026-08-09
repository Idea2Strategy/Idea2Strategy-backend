package com.idea2strategy.backend.persistence.delegation;

import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommand;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommandPort;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommandType;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationDecision;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationExecution;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationMutation;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationResult;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationScope;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationSnapshot;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationStatus;
import com.idea2strategy.backend.application.delegation.DelegationGrantContextPort;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants, replaces, and revokes delegated authorizations in one transaction.
 *
 * <p>The event row is the idempotency receipt: {@code (authorization_id, idempotency_key)} is
 * unique, so a replayed request finds its own event and returns the state it already produced
 * without issuing a second credential. That ordering matters — deciding first and detecting the
 * replay afterwards would mint a credential the caller never receives.
 */
@Component
public class DelegatedAuthorizationJooqAdapter
        implements DelegatedAuthorizationCommandPort, DelegationGrantContextPort {
    private final DSLContext dsl;

    public DelegatedAuthorizationJooqAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public long currentAuthEpoch(UUID accountId) {
        Record epochRow = dsl.fetchOne(
                "select auth_epoch from identity.account_security_states where account_id = ?",
                accountId);
        Long epoch = epochRow == null ? null : epochRow.get("auth_epoch", Long.class);
        if (epoch == null) {
            throw new java.util.NoSuchElementException("Account has no security state");
        }
        return epoch;
    }

    @Override
    public UUID currentDisclosurePolicyDocumentId(String policyCode) {
        Record documentRow = dsl.fetchOne(
                "select id from identity.policy_documents "
                        + "where policy_code = ? and retired_at is null and published_at <= now() "
                        + "order by published_at desc, version desc, id desc limit 1",
                policyCode);
        UUID id = documentRow == null ? null : documentRow.get("id", UUID.class);
        if (id == null) {
            throw new java.util.NoSuchElementException(
                    "No published disclosure document for " + policyCode);
        }
        return id;
    }

    @Override
    @Transactional
    public DelegatedAuthorizationExecution executeAtomically(
            DelegatedAuthorizationCommand command, Instant at, DelegatedAuthorizationDecision decision) {
        UUID subjectId = command.commandType() == DelegatedAuthorizationCommandType.REPLACE
                ? command.replacesAuthorizationId()
                : command.authorizationId();

        Optional<DelegatedAuthorizationResult> replayed = findReceipt(command, subjectId);
        if (replayed.isPresent()) {
            return new DelegatedAuthorizationExecution(replayed.get(), false);
        }

        DelegatedAuthorizationMutation mutation = decision.decide(loadSnapshot(subjectId, command.accountId()));
        apply(mutation, command, at);
        return new DelegatedAuthorizationExecution(
                new DelegatedAuthorizationResult(
                        mutation.authorizationId(),
                        mutation.authorizationVersion(),
                        mutation.status(),
                        mutation.credentialId(),
                        mutation.expiresAt(),
                        Optional.empty()),
                true);
    }

    /**
     * A replay is answered from the authorization the receipt belongs to, never from the request,
     * so a caller repeating a command with drifted arguments still learns what actually happened.
     */
    private Optional<DelegatedAuthorizationResult> findReceipt(
            DelegatedAuthorizationCommand command, UUID subjectId) {
        Record row = dsl.fetchOne(
                "select a.id, a.authorization_version, a.status::text as status, a.expires_at, "
                        + "(select c.id from identity.delegated_credentials c "
                        + " where c.authorization_id = a.id order by c.issued_at desc limit 1) as credential_id "
                        + "from identity.delegated_authorization_events e "
                        + "join identity.delegated_authorizations a on a.id = e.authorization_id "
                        + "where e.idempotency_key = ? and a.account_id = ? "
                        + "and e.authorization_id in (?, ?)",
                command.idempotencyKey(), command.accountId(), subjectId, command.authorizationId());
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new DelegatedAuthorizationResult(
                row.get("id", UUID.class),
                row.get("authorization_version", Long.class),
                DelegatedAuthorizationStatus.valueOf(row.get("status", String.class)),
                row.get("credential_id", UUID.class),
                instant(row, "expires_at"),
                Optional.empty()));
    }

    private Optional<DelegatedAuthorizationSnapshot> loadSnapshot(UUID subjectId, UUID accountId) {
        if (subjectId == null) {
            return Optional.empty();
        }
        Record row = dsl.fetchOne(
                "select id, account_id, authorization_version, status::text as status, "
                        + "auth_epoch_at_grant, expires_at, revoked_at "
                        + "from identity.delegated_authorizations "
                        + "where id = ? and account_id = ? for update",
                subjectId, accountId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new DelegatedAuthorizationSnapshot(
                row.get("id", UUID.class),
                row.get("account_id", UUID.class),
                row.get("authorization_version", Long.class),
                DelegatedAuthorizationStatus.valueOf(row.get("status", String.class)),
                row.get("auth_epoch_at_grant", Long.class),
                instant(row, "expires_at"),
                instant(row, "revoked_at")));
    }

    private void apply(
            DelegatedAuthorizationMutation mutation, DelegatedAuthorizationCommand command, Instant at) {
        if (mutation.status() == DelegatedAuthorizationStatus.REVOKED) {
            revoke(mutation, at);
        } else {
            grant(mutation, at);
        }
        recordEvent(mutation, command, at);
    }

    private void grant(DelegatedAuthorizationMutation mutation, Instant at) {
        if (mutation.replacesAuthorizationId() != null) {
            dsl.execute(
                    "update identity.delegated_authorizations set status = 'REVOKED', "
                            + "revoked_at = ?::timestamptz, revoke_reason_code = 'REPLACED' where id = ?",
                    offset(mutation.predecessorRevokedAt() == null ? at : mutation.predecessorRevokedAt()),
                    mutation.replacesAuthorizationId());
        }

        // AT_TIME whenever the grant carries an expiry, so the authorization check's
        // `expiry_mode <> 'AT_TIME' or expires_at > now` clause actually enforces it.
        String expiryMode = mutation.expiresAt() == null ? "UNTIL_REVOKED" : "AT_TIME";
        dsl.execute(
                "insert into identity.delegated_authorizations ("
                        + "id, account_id, client_label, status, expiry_mode, auth_epoch_at_grant, "
                        + "disclosure_policy_document_id, scope_set_hash, authorized_at, expires_at, "
                        + "authorization_version, replaces_authorization_id, strategy_target_set_hash) "
                        + "values (?, ?, ?, 'ACTIVE', ?::identity.delegated_expiry_mode, ?, ?, ?, "
                        + "?::timestamptz, ?::timestamptz, ?, ?, ?)",
                mutation.authorizationId(), mutation.accountId(), mutation.clientLabel(), expiryMode,
                mutation.authEpochAtGrant(), mutation.disclosurePolicyDocumentId(),
                setHash(mutation.scopes().stream().map(Enum::name).toList()),
                offset(mutation.occurredAt()), offset(mutation.expiresAt()),
                mutation.authorizationVersion(), mutation.replacesAuthorizationId(),
                setHash(mutation.targetStrategyIds().stream().map(UUID::toString).toList()));

        for (DelegatedAuthorizationScope scope : mutation.scopes()) {
            dsl.execute(
                    "insert into identity.delegated_authorization_scopes "
                            + "(authorization_id, scope_code, granted_at) "
                            + "values (?, ?::identity.delegated_scope, ?::timestamptz)",
                    mutation.authorizationId(), scope.name(), offset(mutation.occurredAt()));
        }

        // The owner and access epoch are pinned from the strategy row at grant time. The
        // authorization check compares them back, so a later owner change or access-epoch bump
        // silently stops honouring a delegation instead of carrying it across the change.
        for (UUID strategyId : mutation.targetStrategyIds()) {
            int pinned = dsl.execute(
                    "insert into identity.delegated_authorization_strategy_targets "
                            + "(authorization_id, strategy_id, owner_account_id_at_grant, "
                            + " strategy_access_epoch_at_grant, granted_at) "
                            + "select ?, s.id, s.owner_account_id, s.delegated_access_epoch, ?::timestamptz "
                            + "from strategy.strategies s "
                            + "where s.id = ? and s.owner_account_id = ? and s.mode = 'BASIC' "
                            + "and s.archived_at is null and s.deleted_at is null",
                    mutation.authorizationId(), offset(mutation.occurredAt()), strategyId,
                    mutation.accountId());
            if (pinned != 1) {
                throw new DelegatedStrategyTargetRejectedException(
                        "Delegation targets must be the account's own Basic strategies");
            }
        }

        dsl.execute(
                "insert into identity.delegated_credentials ("
                        + "id, authorization_id, credential_type, token_digest, digest_key_version, "
                        + "issued_at, expires_at) "
                        + "values (?, ?, 'ACCESS_TOKEN', ?, ?, ?::timestamptz, ?::timestamptz)",
                mutation.credentialId(), mutation.authorizationId(), mutation.credentialDigest(),
                mutation.digestKeyVersion(), offset(mutation.occurredAt()),
                offset(mutation.expiresAt() == null ? mutation.occurredAt().plusSeconds(86_400) : mutation.expiresAt()));
    }

    private void revoke(DelegatedAuthorizationMutation mutation, Instant at) {
        dsl.execute(
                "update identity.delegated_authorizations set status = 'REVOKED', "
                        + "revoked_at = ?::timestamptz, revoke_reason_code = ? where id = ?",
                offset(at), mutation.reasonCode(), mutation.authorizationId());
        dsl.execute(
                "update identity.delegated_credentials set revoked_at = ?::timestamptz, "
                        + "revoke_reason_code = ? where authorization_id = ? and revoked_at is null",
                offset(at), mutation.reasonCode(), mutation.authorizationId());
    }

    private void recordEvent(
            DelegatedAuthorizationMutation mutation, DelegatedAuthorizationCommand command, Instant at) {
        dsl.execute(
                "insert into identity.delegated_authorization_events ("
                        + "id, authorization_id, event_sequence, event_type, actor_type, actor_id, "
                        + "reason_code, correlation_id, idempotency_key, occurred_at, payload_document) "
                        + "select ?, ?, coalesce(max(e.event_sequence), 0) + 1, ?, 'USER', ?, ?, ?, ?, "
                        + "?::timestamptz, ?::jsonb "
                        + "from identity.delegated_authorization_events e where e.authorization_id = ?",
                UUID.randomUUID(), mutation.authorizationId(), mutation.commandType().name(),
                mutation.accountId(), mutation.reasonCode(), command.correlationId(),
                command.idempotencyKey(), offset(at),
                // The request hash is what ties the receipt to the arguments that produced it.
                // No scope, target, or credential value is recorded here.
                "{\"requestHash\":\"" + command.requestHash() + "\"}",
                mutation.authorizationId());
    }

    /**
     * Order-independent so the same set never produces two hashes, and a real digest rather than
     * {@code String.hashCode}: these columns are how a later reader decides whether two grants
     * carry the same scopes or targets, and a 32-bit value collides too easily to answer that.
     */
    private static String setHash(Iterable<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        values.forEach(sorted::add);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join(",", sorted).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static java.time.OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Record row, String column) {
        java.time.OffsetDateTime value = row.get(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
