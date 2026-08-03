package com.idea2strategy.backend.persistence.batch;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DurableBatchStore {
    private static final UUID DEPLOYMENT_ACTOR =
            UUID.fromString("a2100000-0000-4000-8000-000000000001");

    public record ClaimedItem(
            UUID itemId,
            UUID runId,
            String categoryCode,
            String sourceKey,
            String sourceVersion,
            Instant dueAt,
            UUID correlationId,
            UUID claimToken,
            int attemptNumber,
            Instant claimedAt,
            Instant claimExpiresAt) {}

    private final JdbcTemplate jdbc;

    public DurableBatchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void publishJobVersion(
            String jobCode, String jobVersion, String categorySetDocument, String contentHash) {
        requireText(jobCode, "jobCode");
        requireText(jobVersion, "jobVersion");
        requireText(categorySetDocument, "categorySetDocument");
        requireText(contentHash, "contentHash");
        jdbc.update("""
                insert into operations.batch_job_versions
                    (job_code, job_version, status, category_set_document, content_hash, published_at)
                values (?, ?, 'ACTIVE', cast(? as jsonb), ?, clock_timestamp())
                on conflict (job_code, job_version) do nothing
                """, jobCode, jobVersion, categorySetDocument, contentHash);
        Map<String, Object> registered = jdbc.queryForMap("""
                select status::text as status, content_hash,
                       category_set_document = cast(? as jsonb) as categories_match
                from operations.batch_job_versions where job_code = ? and job_version = ?
                """, categorySetDocument, jobCode, jobVersion);
        if (!"ACTIVE".equals(registered.get("status"))
                || !contentHash.equals(registered.get("content_hash"))
                || !Boolean.TRUE.equals(registered.get("categories_match"))) {
            throw new BatchConflictException("job version conflicts with the published registry");
        }
        UUID targetId = UUID.nameUUIDFromBytes(
                (jobCode + "|" + jobVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id,
                     reason_code, correlation_id, idempotency_key, after_hash, occurred_at)
                values (?, 'SYSTEM', ?, 'BATCH_JOB_VERSION_PUBLISHED', 'BATCH_JOB_VERSION', ?,
                        'DEPLOYMENT_CONFIGURATION', ?, ?, ?, clock_timestamp())
                on conflict (idempotency_key) do nothing
                """, UUID.randomUUID(), DEPLOYMENT_ACTOR, targetId, targetId,
                "batch-job-version:" + jobCode + ":" + jobVersion, contentHash);
    }

    @Transactional
    public UUID startRun(
            String jobCode,
            String jobVersion,
            String runtimePolicyVersion,
            String triggerId,
            Instant windowStart,
            Instant windowEnd) {
        requireText(jobCode, "jobCode");
        requireText(jobVersion, "jobVersion");
        requireText(runtimePolicyVersion, "runtimePolicyVersion");
        requireText(triggerId, "triggerId");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }
        if (jdbc.queryForObject("""
                select count(*) from operations.batch_job_versions
                where job_code = ? and job_version = ? and status = 'ACTIVE'
                """, Integer.class, jobCode, jobVersion) != 1) {
            throw new BatchConflictException("run requires an active job version");
        }
        Instant persistedWindowStart = windowStart.truncatedTo(ChronoUnit.MICROS);
        Instant persistedWindowEnd = windowEnd.truncatedTo(ChronoUnit.MICROS);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into operations.batch_runs
                    (id, job_code, job_version, runtime_policy_version, trigger_id,
                     window_start, window_end, status, started_at)
                values (?, ?, ?, ?, ?, ?, ?, 'RUNNING', clock_timestamp())
                on conflict (trigger_id) do nothing
                """, id, jobCode, jobVersion, runtimePolicyVersion, triggerId,
                Timestamp.from(persistedWindowStart), Timestamp.from(persistedWindowEnd));
        Map<String, Object> run = jdbc.queryForMap("""
                select id, job_code, job_version, runtime_policy_version,
                       window_start, window_end
                from operations.batch_runs where trigger_id = ?
                """, triggerId);
        if (!jobCode.equals(run.get("job_code"))
                || !jobVersion.equals(run.get("job_version"))
                || !runtimePolicyVersion.equals(run.get("runtime_policy_version"))
                || !persistedWindowStart.equals(((Timestamp) run.get("window_start")).toInstant())
                || !persistedWindowEnd.equals(((Timestamp) run.get("window_end")).toInstant())) {
            throw new BatchConflictException("trigger identity was reused with different run parameters");
        }
        return (UUID) run.get("id");
    }

    @Transactional
    public UUID discover(
            UUID runId,
            String categoryCode,
            String sourceKey,
            String sourceVersion,
            Instant dueAt,
            UUID correlationId) {
        Objects.requireNonNull(runId, "runId");
        requireText(categoryCode, "categoryCode");
        requireText(sourceKey, "sourceKey");
        requireText(sourceVersion, "sourceVersion");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (jdbc.queryForObject("""
                select count(*)
                from operations.batch_runs r
                join operations.batch_job_versions j
                  on j.job_code = r.job_code and j.job_version = r.job_version
                where r.id = ? and r.status = 'RUNNING' and j.status = 'ACTIVE'
                  and j.category_set_document @> jsonb_build_array(cast(? as text))
                """, Integer.class, runId, categoryCode) != 1) {
            throw new BatchConflictException("category is not registered for the active run");
        }
        UUID itemId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into operations.batch_items
                    (id, discovered_by_run_id, category_code, source_key, source_version,
                     due_at, replay_sequence, status, next_attempt_at, correlation_id,
                     first_discovered_at)
                values (?, ?, ?, ?, ?, ?, 0, 'PENDING', ?, ?, clock_timestamp())
                on conflict (category_code, source_key, source_version, due_at, replay_sequence)
                    do nothing
                """, itemId, runId, categoryCode, sourceKey, sourceVersion,
                Timestamp.from(dueAt), Timestamp.from(dueAt), correlationId);
        if (inserted == 1) {
            jdbc.update("update operations.batch_runs set discovered_count = discovered_count + 1 where id = ?", runId);
            return itemId;
        }
        return jdbc.queryForObject("""
                select id from operations.batch_items
                where category_code = ? and source_key = ? and source_version = ?
                  and due_at = ? and replay_sequence = 0
                """, UUID.class, categoryCode, sourceKey, sourceVersion, Timestamp.from(dueAt));
    }

    public String runStatus(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.queryForObject(
                "select status::text from operations.batch_runs where id = ?",
                String.class, runId);
    }

    @Transactional
    public void completeRun(UUID runId, String status) {
        Objects.requireNonNull(runId, "runId");
        requireText(status, "status");
        if (!List.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("unsupported terminal run status");
        }
        int updated = jdbc.update("""
                update operations.batch_runs
                   set status = cast(? as operations.batch_run_status),
                       completed_at = clock_timestamp()
                 where id = ? and status = 'RUNNING'
                """, status, runId);
        if (updated == 0) {
            String current = runStatus(runId);
            if (!status.equals(current)) {
                throw new BatchConflictException("batch run already has a different terminal status");
            }
        }
    }

    @Transactional
    public List<ClaimedItem> claimDue(
            String categoryCode,
            String workerId,
            String runtimePolicyVersion,
            Duration leaseDuration,
            int limit) {
        requireText(categoryCode, "categoryCode");
        requireText(workerId, "workerId");
        requireText(runtimePolicyVersion, "runtimePolicyVersion");
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }

        List<UUID> ids = jdbc.queryForList("""
                select id from operations.batch_items
                where category_code = ?
                  and due_at <= clock_timestamp()
                  and ((status = 'PENDING' and next_attempt_at <= clock_timestamp())
                       or (status = 'CLAIMED' and claim_expires_at <= clock_timestamp()))
                order by next_attempt_at, due_at, id
                for update skip locked
                limit ?
                """, UUID.class, categoryCode, limit);
        List<ClaimedItem> claimed = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Map<String, Object> item = jdbc.queryForMap("""
                    select discovered_by_run_id, category_code, source_key, source_version,
                           due_at, correlation_id, status::text as status, claim_token,
                           attempt_count
                    from operations.batch_items where id = ?
                    """, id);
            Instant now = databaseNow();
            if ("CLAIMED".equals(item.get("status"))) {
                int expired = jdbc.update("""
                        update operations.batch_item_attempts
                        set completed_at = ?, outcome = 'LEASE_EXPIRED', failure_code = 'CLAIM_LEASE_EXPIRED'
                        where batch_item_id = ? and claim_token = ? and completed_at is null
                        """, Timestamp.from(now), id, item.get("claim_token"));
                if (expired != 1) {
                    throw new BatchConflictException("expired claim attempt is missing");
                }
            }
            int attempt = ((Number) item.get("attempt_count")).intValue() + 1;
            UUID token = UUID.randomUUID();
            Instant expiresAt = now.plus(leaseDuration);
            jdbc.update("""
                    update operations.batch_items
                    set status = 'CLAIMED', claim_token = ?, claimed_by = ?, claimed_at = ?,
                        claim_expires_at = ?, attempt_count = ?, next_attempt_at = ?,
                        domain_result_code = null
                    where id = ?
                    """, token, workerId, Timestamp.from(now), Timestamp.from(expiresAt),
                    attempt, Timestamp.from(now), id);
            jdbc.update("""
                    insert into operations.batch_item_attempts
                        (batch_item_id, attempt_number, claim_token, worker_id,
                         runtime_policy_version, correlation_id, claimed_at, claim_expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, attempt, token, workerId, runtimePolicyVersion,
                    item.get("correlation_id"), Timestamp.from(now), Timestamp.from(expiresAt));
            claimed.add(new ClaimedItem(
                    id,
                    (UUID) item.get("discovered_by_run_id"),
                    (String) item.get("category_code"),
                    (String) item.get("source_key"),
                    (String) item.get("source_version"),
                    ((Timestamp) item.get("due_at")).toInstant(),
                    (UUID) item.get("correlation_id"),
                    token,
                    attempt,
                    now,
                    expiresAt));
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public void succeed(UUID itemId, UUID claimToken, String domainResultCode) {
        requireClaim(itemId, claimToken);
        requireText(domainResultCode, "domainResultCode");
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update operations.batch_items
                set status = 'SUCCEEDED', claim_token = null, claimed_by = null,
                    claimed_at = null, claim_expires_at = null, completed_at = ?,
                    domain_result_code = ?, terminal_failure_code = null
                where id = ? and status = 'CLAIMED' and claim_token = ?
                  and claim_expires_at > ?
                """, Timestamp.from(now), domainResultCode, itemId, claimToken, Timestamp.from(now));
        if (updated != 1) throw staleClaim();
        closeAttempt(itemId, claimToken, now, "SUCCEEDED", domainResultCode, null, null);
        jdbc.update("""
                update operations.batch_runs set succeeded_count = succeeded_count + 1
                where id = (select discovered_by_run_id from operations.batch_items where id = ?)
                """, itemId);
    }

    @Transactional
    public void retry(UUID itemId, UUID claimToken, String failureCode, Instant nextAttemptAt) {
        requireClaim(itemId, claimToken);
        requireText(failureCode, "failureCode");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update operations.batch_items
                set status = 'PENDING', claim_token = null, claimed_by = null,
                    claimed_at = null, claim_expires_at = null, next_attempt_at = ?,
                    domain_result_code = null, terminal_failure_code = null
                where id = ? and status = 'CLAIMED' and claim_token = ?
                  and claim_expires_at > ?
                """, Timestamp.from(nextAttemptAt), itemId, claimToken, Timestamp.from(now));
        if (updated != 1) throw staleClaim();
        closeAttempt(itemId, claimToken, now, "RETRY_SCHEDULED", null, failureCode, nextAttemptAt);
    }

    @Transactional
    public void quarantine(UUID itemId, UUID claimToken, String failureCode) {
        requireClaim(itemId, claimToken);
        requireText(failureCode, "failureCode");
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update operations.batch_items
                set status = 'QUARANTINED', claim_token = null, claimed_by = null,
                    claimed_at = null, claim_expires_at = null, completed_at = ?,
                    domain_result_code = null, terminal_failure_code = ?
                where id = ? and status = 'CLAIMED' and claim_token = ?
                  and claim_expires_at > ?
                """, Timestamp.from(now), failureCode, itemId, claimToken, Timestamp.from(now));
        if (updated != 1) throw staleClaim();
        closeAttempt(itemId, claimToken, now, "QUARANTINED", null, failureCode, null);
        jdbc.update("""
                update operations.batch_runs set quarantined_count = quarantined_count + 1
                where id = (select discovered_by_run_id from operations.batch_items where id = ?)
                """, itemId);
    }

    @Transactional
    public void saveCheckpoint(
            String jobCode,
            String jobVersion,
            String categoryCode,
            String shardKey,
            Instant cursorDueAt,
            String cursorSourceKey,
            UUID lastRunId,
            long scannedCount) {
        requireText(jobCode, "jobCode");
        requireText(jobVersion, "jobVersion");
        requireText(categoryCode, "categoryCode");
        requireText(shardKey, "shardKey");
        Objects.requireNonNull(cursorDueAt, "cursorDueAt");
        requireText(cursorSourceKey, "cursorSourceKey");
        Objects.requireNonNull(lastRunId, "lastRunId");
        if (scannedCount < 0) throw new IllegalArgumentException("scannedCount must be nonnegative");
        jdbc.update("""
                insert into operations.batch_run_checkpoints
                    (job_code, job_version, category_code, shard_key, cursor_due_at,
                     cursor_source_key, last_run_id, scanned_count, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                on conflict (job_code, job_version, category_code, shard_key) do update
                set cursor_due_at = excluded.cursor_due_at,
                    cursor_source_key = excluded.cursor_source_key,
                    last_run_id = excluded.last_run_id,
                    scanned_count = excluded.scanned_count,
                    updated_at = clock_timestamp()
                """, jobCode, jobVersion, categoryCode, shardKey, Timestamp.from(cursorDueAt),
                cursorSourceKey, lastRunId, scannedCount);
    }

    private void closeAttempt(
            UUID itemId,
            UUID claimToken,
            Instant completedAt,
            String outcome,
            String domainResultCode,
            String failureCode,
            Instant nextAttemptAt) {
        int updated = jdbc.update("""
                update operations.batch_item_attempts
                set completed_at = ?, outcome = cast(? as operations.batch_attempt_outcome),
                    domain_result_code = ?, failure_code = ?, next_attempt_at = ?
                where batch_item_id = ? and claim_token = ? and completed_at is null
                """, Timestamp.from(completedAt), outcome, domainResultCode, failureCode,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), itemId, claimToken);
        if (updated != 1) {
            throw new BatchConflictException("current claim attempt is missing");
        }
    }

    public Instant databaseNow() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private static void requireClaim(UUID itemId, UUID claimToken) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(claimToken, "claimToken");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static BatchConflictException staleClaim() {
        return new BatchConflictException("stale or expired batch claim");
    }

    public static class BatchConflictException extends RuntimeException {
        public BatchConflictException(String message) {
            super(message);
        }
    }
}
