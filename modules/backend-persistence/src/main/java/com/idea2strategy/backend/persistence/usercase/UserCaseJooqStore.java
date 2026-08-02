package com.idea2strategy.backend.persistence.usercase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.usercase.UserCaseCommand;
import com.idea2strategy.backend.application.usercase.UserCaseEvidenceOwnershipPort;
import com.idea2strategy.backend.application.usercase.UserCaseEvidenceReference;
import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseStore;
import com.idea2strategy.backend.application.usercase.UserCaseSupplementCommand;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import com.idea2strategy.backend.application.usercase.UserCaseView;
import com.idea2strategy.backend.application.usercase.VerifiedUserCaseEvidence;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Transactional;

public class UserCaseJooqStore implements UserCaseStore {
    private static final String SUBMIT = "SUBMIT";
    private static final String ADD_EVIDENCE = "ADD_EVIDENCE";

    private final DSLContext dsl;
    private final UserCaseEvidenceOwnershipPort ownership;
    private final ObjectMapper json;

    public UserCaseJooqStore(
            DSLContext dsl,
            UserCaseEvidenceOwnershipPort ownership,
            ObjectMapper json) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    @Transactional
    public CommandResult submit(UserCaseCommand command, Instant now) {
        lockReceiptScope(command.accountId(), SUBMIT, command.idempotencyKey());
        CommandResult replay = replay(
                command.accountId(), SUBMIT, command.idempotencyKey(), command.requestHash());
        if (replay != null) {
            return replay;
        }
        if (!activeAccount(command.accountId())) {
            return failed(CommandResult.Outcome.RESOURCE_NOT_AVAILABLE);
        }

        List<VerifiedUserCaseEvidence> evidence = verify(
                command.accountId(), command.evidenceReferences(), now);
        if (evidence == null) {
            return failed(CommandResult.Outcome.RESOURCE_NOT_AVAILABLE);
        }

        UUID caseId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime at = utc(now);
        dsl.execute("""
                insert into operations.cases
                    (id, account_id, case_type, status, subject, case_version,
                     current_event_sequence, last_case_event_id, created_at, updated_at)
                values (?, ?, ?::operations.case_type, 'OPEN', ?, 1, 1, ?,
                        ?::timestamptz, ?::timestamptz)
                """, caseId, command.accountId(), command.type().name(), command.subject(), eventId, at, at);
        ObjectNode eventPayload = json.createObjectNode().put("description", command.description());
        insertEvent(eventId, caseId, command.accountId(), 1, null, "SUBMITTED", "OPEN",
                command.correlationId(), eventPayload, at);
        insertEvidence(caseId, eventId, command.accountId(), evidence, at);
        UserCaseView view = new UserCaseView(
                caseId, command.accountId(), command.type(), UserCaseStatus.OPEN, 1,
                evidence.stream().map(VerifiedUserCaseEvidence::storageObjectId).toList(), now);
        insertOutbox(caseId, 1, "USER_CASE_SUBMITTED",
                command.accountId() + ":" + SUBMIT + ":" + command.idempotencyKey(), view, at);
        insertReceipt(command.accountId(), SUBMIT, command.idempotencyKey(), command.requestHash(),
                caseId, eventId, 201, "CASE_SUBMITTED", view, at);
        return new CommandResult(CommandResult.Outcome.APPLIED, view);
    }

    @Override
    @Transactional
    public CommandResult supplement(UserCaseSupplementCommand command, Instant now) {
        lockReceiptScope(command.accountId(), ADD_EVIDENCE, command.idempotencyKey());
        CommandResult replay = replay(
                command.accountId(), ADD_EVIDENCE, command.idempotencyKey(), command.requestHash());
        if (replay != null) {
            return replay;
        }

        Record current = dsl.fetchOne("""
                select id, account_id, case_type::text as case_type, status::text as status,
                       subject, case_version, current_event_sequence, last_case_event_id,
                       updated_at
                from operations.cases where id = ? and account_id = ? for update
                """, command.caseId(), command.accountId());
        if (current == null) {
            return failed(CommandResult.Outcome.RESOURCE_NOT_AVAILABLE);
        }
        if (!"NEEDS_INFORMATION".equals(current.get("status", String.class))) {
            return failed(CommandResult.Outcome.TRANSITION_NOT_ALLOWED);
        }
        long version = current.get("case_version", Long.class);
        if (version != command.expectedVersion()) {
            return failed(CommandResult.Outcome.STALE_VERSION);
        }
        List<VerifiedUserCaseEvidence> evidence = verify(
                command.accountId(), command.evidenceReferences(), now);
        if (evidence == null) {
            return failed(CommandResult.Outcome.RESOURCE_NOT_AVAILABLE);
        }

        int sequence = current.get("current_event_sequence", Integer.class) + 1;
        UUID previousEventId = current.get("last_case_event_id", UUID.class);
        UUID eventId = UUID.randomUUID();
        OffsetDateTime at = utc(now);
        ObjectNode eventPayload = json.createObjectNode().put("evidenceCount", evidence.size());
        insertEvent(eventId, command.caseId(), command.accountId(), sequence, previousEventId,
                "EVIDENCE_ADDED", "OPEN", command.correlationId(), eventPayload, at);
        dsl.execute("""
                update operations.cases
                set status = 'OPEN', case_version = case_version + 1,
                    current_event_sequence = ?, last_case_event_id = ?, updated_at = ?::timestamptz
                where id = ? and account_id = ? and case_version = ?
                """, sequence, eventId, at, command.caseId(), command.accountId(), command.expectedVersion());
        insertEvidence(command.caseId(), eventId, command.accountId(), evidence, at);
        List<UUID> allEvidence = evidenceIds(command.accountId(), command.caseId());
        UserCaseView view = new UserCaseView(
                command.caseId(), command.accountId(),
                UserCaseType.valueOf(current.get("case_type", String.class)),
                UserCaseStatus.OPEN, version + 1, allEvidence, now);
        insertOutbox(command.caseId(), sequence, "USER_CASE_EVIDENCE_ADDED",
                command.accountId() + ":" + ADD_EVIDENCE + ":" + command.idempotencyKey(), view, at);
        insertReceipt(command.accountId(), ADD_EVIDENCE, command.idempotencyKey(), command.requestHash(),
                command.caseId(), eventId, 200, "CASE_EVIDENCE_ADDED", view, at);
        return new CommandResult(CommandResult.Outcome.APPLIED, view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCaseView> findOwned(UUID accountId, UUID caseId) {
        Record row = dsl.fetchOne("""
                select id, account_id, case_type::text as case_type, status::text as status,
                       case_version, updated_at
                from operations.cases where account_id = ? and id = ?
                """, accountId, caseId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new UserCaseView(
                row.get("id", UUID.class), row.get("account_id", UUID.class),
                UserCaseType.valueOf(row.get("case_type", String.class)),
                UserCaseStatus.valueOf(row.get("status", String.class)),
                row.get("case_version", Long.class), evidenceIds(accountId, caseId),
                row.get("updated_at", OffsetDateTime.class).toInstant()));
    }

    private List<VerifiedUserCaseEvidence> verify(
            UUID accountId, List<UserCaseEvidenceReference> requested, Instant now) {
        var seen = new HashSet<UUID>();
        var verified = new ArrayList<VerifiedUserCaseEvidence>(requested.size());
        for (UserCaseEvidenceReference reference : requested) {
            if (!seen.add(reference.storageObjectId())) {
                return null;
            }
            Optional<VerifiedUserCaseEvidence> result = ownership.verifyOwnedAvailable(accountId, reference, now);
            if (result.isEmpty()) {
                return null;
            }
            VerifiedUserCaseEvidence proof = result.orElseThrow();
            if (!proof.ownerAccountId().equals(accountId)
                    || !proof.storageObjectId().equals(reference.storageObjectId())
                    || !proof.sourceDomain().equals(reference.sourceDomain())
                    || !proof.sourceResourceId().equals(reference.sourceResourceId())
                    || proof.verifiedAt().isAfter(now)) {
                return null;
            }
            verified.add(proof);
        }
        return List.copyOf(verified);
    }

    private void insertEvent(
            UUID eventId, UUID caseId, UUID accountId, int sequence, UUID previousEventId,
            String eventType, String status, UUID correlationId, JsonNode payload, OffsetDateTime at) {
        dsl.execute("""
                insert into operations.case_events
                    (id, case_id, account_id, event_sequence, previous_event_id, actor_type,
                     actor_id, event_type, resulting_status, visibility, correlation_id,
                     payload_document, created_at)
                values (?, ?, ?, ?, ?, 'ACCOUNT', ?, ?::operations.case_event_type,
                        ?::operations.case_status, 'USER_VISIBLE', ?, ?::jsonb, ?::timestamptz)
                """, eventId, caseId, accountId, sequence, previousEventId, accountId,
                eventType, status, correlationId, stringify(payload), at);
    }

    private void insertEvidence(
            UUID caseId, UUID eventId, UUID accountId,
            List<VerifiedUserCaseEvidence> evidence, OffsetDateTime attachedAt) {
        for (VerifiedUserCaseEvidence proof : evidence) {
            dsl.execute("""
                    insert into operations.case_evidence_references
                        (case_id, account_id, case_event_id, storage_object_id,
                         source_domain, source_resource_id, owner_account_id,
                         ownership_policy_version, ownership_verified_at, attached_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                    """, caseId, accountId, eventId, proof.storageObjectId(), proof.sourceDomain(),
                    proof.sourceResourceId(), proof.ownerAccountId(), proof.ownershipPolicyVersion(),
                    utc(proof.verifiedAt()), attachedAt);
        }
    }

    private void insertOutbox(
            UUID caseId, long sequence, String eventType, String commandKey,
            UserCaseView view, OffsetDateTime at) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("caseType", view.type().name());
        payload.put("status", view.status().name());
        payload.put("caseVersion", view.version());
        dsl.execute("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                     event_schema_version, payload_document, idempotency_key, created_at)
                values (?, 'OPERATIONS_CASE', ?, ?, ?, '1', ?::jsonb, ?, ?::timestamptz)
                """, UUID.randomUUID(), caseId, sequence, eventType, stringify(payload),
                "case:" + commandKey, at);
    }

    private void insertReceipt(
            UUID accountId, String type, String key, String hash, UUID caseId, UUID eventId,
            int status, String code, UserCaseView view, OffsetDateTime at) {
        dsl.execute("""
                insert into operations.case_command_receipts
                    (account_id, command_type, idempotency_key, request_hash, case_id,
                     case_event_id, response_status, response_code, response_document, completed_at)
                values (?, ?::operations.case_command_type, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?::timestamptz)
                """, accountId, type, key, hash, caseId, eventId, status, code,
                stringify(encodeView(view)), at);
    }

    private CommandResult replay(UUID accountId, String type, String key, String hash) {
        Record receipt = dsl.fetchOne("""
                select request_hash, response_document::text as response_document
                from operations.case_command_receipts
                where account_id = ? and command_type = ?::operations.case_command_type
                  and idempotency_key = ?
                """, accountId, type, key);
        if (receipt == null) {
            return null;
        }
        if (!hash.equals(receipt.get("request_hash", String.class))) {
            return failed(CommandResult.Outcome.IDEMPOTENCY_CONFLICT);
        }
        return new CommandResult(CommandResult.Outcome.REPLAYED,
                decodeView(receipt.get("response_document", String.class)));
    }

    private void lockReceiptScope(UUID accountId, String type, String key) {
        dsl.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                accountId + ":" + type + ":" + key);
    }

    private boolean activeAccount(UUID accountId) {
        return dsl.fetchOne("""
                select 1 from identity.accounts
                where id = ? and lifecycle_status = 'ACTIVE' for share
                """, accountId) != null;
    }

    private List<UUID> evidenceIds(UUID accountId, UUID caseId) {
        return dsl.fetch("""
                select storage_object_id from operations.case_evidence_references
                where account_id = ? and case_id = ? order by attached_at, storage_object_id
                """, accountId, caseId).getValues("storage_object_id", UUID.class);
    }

    private ObjectNode encodeView(UserCaseView view) {
        ObjectNode node = json.createObjectNode();
        node.put("id", view.id().toString());
        node.put("accountId", view.accountId().toString());
        node.put("type", view.type().name());
        node.put("status", view.status().name());
        node.put("version", view.version());
        node.put("updatedAt", view.updatedAt().toString());
        ArrayNode evidence = node.putArray("evidenceObjectIds");
        view.evidenceObjectIds().forEach(id -> evidence.add(id.toString()));
        return node;
    }

    private UserCaseView decodeView(String document) {
        try {
            JsonNode node = json.readTree(document);
            var evidence = new ArrayList<UUID>();
            node.path("evidenceObjectIds").forEach(value -> evidence.add(UUID.fromString(value.asText())));
            return new UserCaseView(
                    UUID.fromString(node.path("id").asText()),
                    UUID.fromString(node.path("accountId").asText()),
                    UserCaseType.valueOf(node.path("type").asText()),
                    UserCaseStatus.valueOf(node.path("status").asText()),
                    node.path("version").asLong(), evidence,
                    Instant.parse(node.path("updatedAt").asText()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored case receipt is invalid", exception);
        }
    }

    private String stringify(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Case JSON could not be serialized", exception);
        }
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static CommandResult failed(CommandResult.Outcome outcome) {
        return new CommandResult(outcome, null);
    }
}
