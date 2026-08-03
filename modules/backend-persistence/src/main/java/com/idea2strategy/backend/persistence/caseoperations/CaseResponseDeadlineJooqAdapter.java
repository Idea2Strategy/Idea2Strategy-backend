package com.idea2strategy.backend.persistence.caseoperations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.caseoperations.CaseResponseDeadlinePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CaseResponseDeadlineJooqAdapter implements CaseResponseDeadlinePort {
    private static final UUID SYSTEM_ACTOR_ID =
            UUID.fromString("a2000000-0000-4000-8000-000000000021");
    private static final String EVENT_TYPE = "INFORMATION_RESPONSE_DEADLINE_EXPIRED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CaseResponseDeadlineJooqAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    @Transactional
    public List<Identity> findDue(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("CASE_DEADLINE_LIMIT_INVALID");
        return jdbc.query("""
                select id, case_version, response_deadline_at
                from operations.cases
                where status = 'NEEDS_INFORMATION'
                  and response_deadline_at <= clock_timestamp()
                order by response_deadline_at, id
                limit ? for update skip locked
                """, (row, index) -> new Identity(
                row.getObject("id", UUID.class), row.getLong("case_version"),
                row.getObject("response_deadline_at", OffsetDateTime.class).toInstant()), limit);
    }

    @Override
    @Transactional
    public Result expire(Identity identity, UUID correlationId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(correlationId, "correlationId");
        Map<String, Object> current = lockCase(identity.caseId());
        Result replay = replay(identity);
        if (replay != null) return replay;

        Instant databaseNow = jdbc.queryForObject(
                "select clock_timestamp()", OffsetDateTime.class).toInstant();
        Instant storedDeadline = instant(current.get("response_deadline_at"));
        boolean matches = "NEEDS_INFORMATION".equals(current.get("status"))
                && ((Number) current.get("case_version")).longValue() == identity.expectedCaseVersion()
                && storedDeadline != null
                && storedDeadline.equals(identity.responseDeadlineAt())
                && !databaseNow.isBefore(identity.responseDeadlineAt());
        if (!matches) {
            insertReceipt(identity, Result.Status.ALREADY_TRANSITIONED, null, correlationId, databaseNow);
            return new Result(Result.Status.ALREADY_TRANSITIONED, identity, null, databaseNow);
        }

        UUID eventId = UUID.randomUUID();
        UUID accountId = (UUID) current.get("account_id");
        UUID previousEventId = (UUID) current.get("last_case_event_id");
        long nextVersion = identity.expectedCaseVersion() + 1;
        int nextSequence = ((Number) current.get("current_event_sequence")).intValue() + 1;
        String policyVersion = (String) current.get("deadline_policy_version");
        ObjectNode payload = json.createObjectNode()
                .put("caseId", identity.caseId().toString())
                .put("reasonCode", EVENT_TYPE)
                .put("expiredDeadline", identity.responseDeadlineAt().toString())
                .put("deadlinePolicyVersion", policyVersion);

        jdbc.update("""
                insert into operations.case_events
                    (id, case_id, account_id, event_sequence, previous_event_id, actor_type,
                     actor_id, event_type, resulting_status, visibility, reason_code,
                     correlation_id, payload_document, created_at)
                values (?, ?, ?, ?, ?, 'SYSTEM', ?, cast(? as operations.case_event_type),
                        'UNDER_REVIEW', 'USER_VISIBLE', ?, ?, cast(? as jsonb), ?)
                """, eventId, identity.caseId(), accountId, nextSequence, previousEventId,
                SYSTEM_ACTOR_ID, EVENT_TYPE, EVENT_TYPE, correlationId, write(payload),
                Timestamp.from(databaseNow));
        int changed = jdbc.update("""
                update operations.cases
                set status = 'UNDER_REVIEW', case_version = ?, current_event_sequence = ?,
                    last_case_event_id = ?, response_deadline_at = null,
                    deadline_policy_version = null, updated_at = ?
                where id = ? and case_version = ? and status = 'NEEDS_INFORMATION'
                  and response_deadline_at = ?
                """, nextVersion, nextSequence, eventId, Timestamp.from(databaseNow),
                identity.caseId(), identity.expectedCaseVersion(), Timestamp.from(identity.responseDeadlineAt()));
        if (changed != 1) throw new IllegalStateException("CASE_DEADLINE_CONCURRENT_MUTATION");

        insertReceipt(identity, Result.Status.APPLIED, eventId, correlationId, databaseNow);
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                     event_schema_version, payload_document, idempotency_key, created_at)
                values (?, 'OPERATIONS_CASE', ?, ?, ?, '1', cast(? as jsonb), ?, ?)
                """, UUID.randomUUID(), identity.caseId(), nextVersion, EVENT_TYPE, write(payload),
                "case-deadline:" + identity.caseId() + ":" + identity.expectedCaseVersion()
                        + ":" + identity.responseDeadlineAt(), Timestamp.from(databaseNow));
        return new Result(Result.Status.APPLIED, identity, eventId, databaseNow);
    }

    private Map<String, Object> lockCase(UUID caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, status::text, case_version, current_event_sequence,
                       last_case_event_id, response_deadline_at, deadline_policy_version
                from operations.cases where id = ? for update
                """, caseId);
        if (rows.isEmpty()) throw new IllegalStateException("CASE_DEADLINE_CASE_NOT_AVAILABLE");
        return rows.getFirst();
    }

    private Result replay(Identity identity) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select decision_status, case_event_id, decided_at
                from operations.case_deadline_receipts
                where case_id = ? and expected_case_version = ? and response_deadline_at = ?
                """, identity.caseId(), identity.expectedCaseVersion(),
                Timestamp.from(identity.responseDeadlineAt()));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        return new Result(Result.Status.valueOf((String) row.get("decision_status")), identity,
                (UUID) row.get("case_event_id"), instant(row.get("decided_at")));
    }

    private void insertReceipt(Identity identity, Result.Status status, UUID eventId,
            UUID correlationId, Instant decidedAt) {
        jdbc.update("""
                insert into operations.case_deadline_receipts
                    (case_id, expected_case_version, response_deadline_at, decision_status,
                     case_event_id, correlation_id, decided_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, identity.caseId(), identity.expectedCaseVersion(),
                Timestamp.from(identity.responseDeadlineAt()), status.name(), eventId,
                correlationId, Timestamp.from(decidedAt));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CASE_DEADLINE_JSON_INVALID", exception);
        }
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("CASE_DEADLINE_TIMESTAMP_INVALID");
    }
}
