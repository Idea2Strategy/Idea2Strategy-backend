package com.idea2strategy.backend.persistence.sanction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.accountsanction.AccountAccessRevocationPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthorizationPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommand;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionDecision;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionExpiryPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionIdempotencyConflictException;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionOutboxPublicationPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionResult;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class AccountSanctionJdbcAdapter implements
        AccountSanctionCommandPort, AccountAccessRevocationPort,
        AccountSanctionOutboxPublicationPort, AccountSanctionExpiryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AccountSanctionJdbcAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    @Transactional
    public AccountSanctionResult executeAtomically(
            AccountSanctionCommand command,
            Instant evaluatedAt,
            AccountSanctionAuthorizationPort.Decision authorization,
            AccountSanctionDecision decision,
            TransactionalEffects effects) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> {
            resultSet.next();
            return null;
        }, command.accountId() + ":" + command.type() + ":" + command.idempotencyKey());
        List<Receipt> receipts = jdbc.query("""
                select request_hash, response_document::text
                from identity.account_sanction_command_receipts
                where account_id = ? and command_type = ? and idempotency_key = ?
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)),
                command.accountId(), command.type().name(), command.idempotencyKey());
        if (!receipts.isEmpty()) {
            Receipt receipt = receipts.getFirst();
            if (!receipt.hash().equals(command.requestHash())) {
                throw new AccountSanctionIdempotencyConflictException();
            }
            return read(receipt.document());
        }

        jdbc.update("""
                insert into identity.account_sanction_heads(account_id, aggregate_version)
                values (?, 0) on conflict (account_id) do nothing
                """, command.accountId());
        Long version = jdbc.queryForObject("""
                select aggregate_version from identity.account_sanction_heads
                where account_id = ? for update
                """, Long.class, command.accountId());
        AccountSanctionState state = load(command.accountId(), version == null ? 0 : version);
        AccountSanctionResult result = decision.decide(state, authorization);
        if (result.status() == AccountSanctionResult.Status.APPLIED) {
            persist(result.mutation());
            int changed = jdbc.update("""
                    update identity.account_sanction_heads
                    set aggregate_version = ?, updated_at = ?
                    where account_id = ? and aggregate_version = ?
                    """, result.mutation().newVersion(), Timestamp.from(evaluatedAt), command.accountId(),
                    result.mutation().previousVersion());
            if (changed != 1) {
                throw new IllegalStateException("sanction aggregate version changed while locked");
            }
            effects.publish(result);
        }
        audit(command, result, evaluatedAt);
        jdbc.update("""
                insert into identity.account_sanction_command_receipts
                    (account_id, command_type, idempotency_key, request_hash, sanction_id,
                     correlation_id, response_document, completed_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, command.accountId(), command.type().name(), command.idempotencyKey(),
                command.requestHash(), command.sanctionId(), command.correlationId(), write(result),
                Timestamp.from(evaluatedAt));
        return result;
    }

    @Override
    public void revoke(Effect effect) {
        if (effect.bumpAuthEpoch()) {
            jdbc.update("""
                    insert into identity.account_security_states
                        (account_id, auth_epoch, credentials_revoked_before, updated_at)
                    values (?, 2, ?, ?)
                    on conflict (account_id) do update
                    set auth_epoch = identity.account_security_states.auth_epoch + 1,
                        credentials_revoked_before = excluded.credentials_revoked_before,
                        updated_at = excluded.updated_at
                    """, effect.accountId(), Timestamp.from(effect.occurredAt()), Timestamp.from(effect.occurredAt()));
        }
        if (effect.revokeAllCredentials()) {
            jdbc.update("""
                    update identity.refresh_token_families
                    set revoked_at = ?, revoke_reason_code = ?
                    where account_id = ? and revoked_at is null
                    """, Timestamp.from(effect.occurredAt()), effect.reasonCode(), effect.accountId());
        }
    }

    @Override
    public void publish(List<Message> messages) {
        for (Message message : messages) {
            String payload = """
                    {"accountId":"%s","sanctionId":"%s","correlationId":"%s"}
                    """.formatted(message.accountId(), message.sanctionId(), message.correlationId()).strip();
            jdbc.update("""
                    insert into operations.outbox_messages
                        (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                         event_schema_version, payload_document, idempotency_key, created_at)
                    values (?, 'ACCOUNT_SANCTION', ?, null, ?, '1', ?::jsonb, ?, ?)
                    """, UUID.randomUUID(), message.sanctionId(), message.type(), payload,
                    message.deduplicationKey(), Timestamp.from(message.occurredAt()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueSanction> findDue(int limit) {
        return jdbc.query("""
                select sanction.account_id, sanction.id, sanction.expires_at, head.aggregate_version
                from identity.account_sanctions sanction
                join identity.account_sanction_heads head on head.account_id = sanction.account_id
                where sanction.status = 'ACTIVE'
                  and sanction.sanction_type = 'SUSPENSION'
                  and sanction.expires_at <= current_timestamp
                order by sanction.expires_at, sanction.account_id, sanction.id
                limit ?
                """, (rs, row) -> new DueSanction(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getTimestamp(3).toInstant(), rs.getLong(4)), limit);
    }

    private AccountSanctionState load(UUID accountId, long version) {
        List<AccountSanctionState.Sanction> sanctions = jdbc.query("""
                select id, sanction_type, status::text, reason_code, applied_at,
                       effective_at, expires_at, source_case_id
                from identity.account_sanctions where account_id = ? order by applied_at, id
                """, (rs, row) -> new AccountSanctionState.Sanction(
                rs.getObject(1, UUID.class),
                AccountSanctionState.Type.valueOf(rs.getString(2)),
                AccountSanctionState.Status.valueOf(rs.getString(3)),
                rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                rs.getObject(8, UUID.class)), accountId);
        return new AccountSanctionState(accountId, version, sanctions);
    }

    private void persist(AccountSanctionResult.Mutation mutation) {
        if (mutation.kind() == AccountSanctionResult.Mutation.Kind.APPLY) {
            jdbc.update("""
                    insert into identity.account_sanctions
                        (id, account_id, sanction_type, status, reason_code,
                         applied_by_operator_id, applied_at, effective_at, expires_at,
                         source_case_id, status_changed_at)
                    values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                    """, mutation.sanctionId(), mutation.accountId(), mutation.sanctionType().name(),
                    mutation.sanctionReasonCode(), mutation.actorOperatorId(),
                    Timestamp.from(mutation.appliedAt()), Timestamp.from(mutation.effectiveAt()),
                    mutation.expiresAt() == null ? null : Timestamp.from(mutation.expiresAt()),
                    mutation.sourceCaseId(), Timestamp.from(mutation.occurredAt()));
        } else {
            int changed = jdbc.update("""
                    update identity.account_sanctions set status = ?::identity.sanction_status,
                        status_changed_at = ? where id = ? and account_id = ? and status = 'ACTIVE'
                    """, mutation.afterStatus().name(), Timestamp.from(mutation.occurredAt()),
                    mutation.sanctionId(), mutation.accountId());
            if (changed != 1) {
                throw new IllegalStateException("sanction transition lost its locked active row");
            }
        }
        Long sequence = jdbc.queryForObject("""
                select coalesce(max(event_sequence), 0) + 1
                from identity.account_sanction_events where sanction_id = ?
                """, Long.class, mutation.sanctionId());
        jdbc.update("""
                insert into identity.account_sanction_events
                    (id, sanction_id, event_sequence, event_type, actor_operator_id,
                     reason_code, occurred_at, account_id, correlation_id,
                     previous_status, resulting_status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::identity.sanction_status,
                        ?::identity.sanction_status)
                """, UUID.randomUUID(), mutation.sanctionId(), sequence,
                mutation.kind() == AccountSanctionResult.Mutation.Kind.APPLY ? "APPLIED" :
                        mutation.kind() == AccountSanctionResult.Mutation.Kind.LIFT ? "LIFTED" : "EXPIRED",
                mutation.actorOperatorId(), mutation.eventReasonCode(), Timestamp.from(mutation.occurredAt()),
                mutation.accountId(), mutation.correlationId(),
                mutation.beforeStatus() == null ? null : mutation.beforeStatus().name(),
                mutation.afterStatus().name());
    }

    private void audit(AccountSanctionCommand command, AccountSanctionResult result, Instant now) {
        if (command.requestContext() == null) {
            return;
        }
        String afterHash = result.mutation() == null ? null : sha256(write(result.mutation()));
        jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id,
                     reason_code, correlation_id, idempotency_key, after_hash, occurred_at)
                values (?, 'OPERATOR', ?, ?, 'ACCOUNT_SANCTION', ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), command.requestContext().operatorId(), command.type().name(),
                command.sanctionId(), result.code(), command.correlationId(),
                "sanction:" + command.accountId() + ":" + command.type() + ":" + command.idempotencyKey(),
                afterHash, Timestamp.from(now));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("sanction result serialization failed", exception);
        }
    }

    private AccountSanctionResult read(String value) {
        try {
            return json.readValue(value, AccountSanctionResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored sanction receipt is invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Receipt(String hash, String document) {}
}
