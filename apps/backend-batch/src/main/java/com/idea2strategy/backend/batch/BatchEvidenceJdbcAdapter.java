package com.idea2strategy.backend.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.batch.BatchFailureHandoffPort;
import com.idea2strategy.backend.application.batch.BatchRunEvidencePort;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class BatchEvidenceJdbcAdapter implements BatchFailureHandoffPort, BatchRunEvidencePort {
    private static final Logger LOG = LoggerFactory.getLogger(BatchEvidenceJdbcAdapter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BatchEvidenceJdbcAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void handoff(Failure failure) {
        // The audit row keeps the stable reason code; the cause is logged beside it. Without this an
        // expired sanction retried every minute for three hours and the only record was 180 identical
        // UNCLASSIFIED_EXECUTION_FAILURE rows with nothing saying what had failed (#264). Logging is
        // the adapter's job because the application module deliberately has no logger.
        if (failure.diagnostic() != null) {
            LOG.error(
                    "Batch item failed: category={} itemId={} attempt={} disposition={} code={} "
                            + "runId={} correlationId={} cause={}",
                    failure.category(), failure.itemId(), failure.attemptNumber(),
                    failure.disposition(), failure.failureCode(), failure.runId(),
                    failure.correlationId(), failure.diagnostic());
        }
        record(failure.runId(), failure.correlationId(), "BATCH_" + failure.disposition(),
                failure.category().name(), failure.itemId(), failure.failureCode(),
                "batch-failure:" + failure.runId() + ":" + failure.category() + ":" + failure.itemId()
                        + ":" + failure.attemptNumber(), hash(failure));
    }

    @Override
    public void record(DeadlineBatchOrchestrator.RunSummary summary) {
        record(summary.runId(), summary.correlationId(), "BATCH_RUN_COMPLETED", "BATCH_RUN",
                summary.runId().toString(), summary.categoryFailures() == 0 ? "COMPLETED" : "PARTIAL_FAILURE",
                "batch-run:" + summary.runId(), hash(summary));
    }

    private void record(
            UUID actorId, UUID correlationId, String action, String domain, String stableTarget,
            String reason, String idempotencyKey, String afterHash) {
        UUID targetId;
        try { targetId = UUID.fromString(stableTarget); }
        catch (IllegalArgumentException ignored) {
            targetId = UUID.nameUUIDFromBytes(stableTarget.getBytes(StandardCharsets.UTF_8));
        }
        jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id,
                     reason_code, correlation_id, idempotency_key, after_hash, occurred_at)
                values (?, 'SYSTEM', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (idempotency_key) do nothing
                """, UUID.randomUUID(), actorId, action, domain, targetId, reason, correlationId,
                idempotencyKey, afterHash, Timestamp.from(databaseNow()));
    }

    private Instant databaseNow() {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject("select clock_timestamp()", Timestamp.class)).toInstant();
    }

    private String hash(Object value) {
        try {
            byte[] document = json.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("batch evidence hash failed", exception);
        }
    }
}
