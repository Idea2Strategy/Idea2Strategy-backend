package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimRequest;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ItemResult;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ItemStatus;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.WorkItem;
import com.idea2strategy.backend.persistence.batch.DurableBatchStore;
import com.idea2strategy.backend.persistence.batch.DurableBatchStore.ClaimedItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Production runtime that places the approved durable item/lease boundary in front of the
 * existing idempotent A-domain transition ports.
 */
public final class DurableDeadlineBatchRuntime {
    static final List<String> REGISTERED_CATEGORIES = List.of(
            "CASE_RESPONSE_DEADLINE",
            "DELEGATED_AUTHORIZATION_EXPIRY",
            "DELEGATED_CREDENTIAL_EXPIRY",
            "NOTIFICATION_RETRY",
            "SANCTION_EXPIRY",
            "SESSION_EXPIRY");
    static final String CATEGORY_SET_DOCUMENT = "[\"" + String.join("\",\"", REGISTERED_CATEGORIES) + "\"]";

    public record Settings(
            String jobCode,
            String jobVersion,
            String jobContentHash,
            String runtimePolicyVersion,
            String workerId,
            Duration leaseDuration,
            Duration retryDelay,
            Duration discoveryOverlap,
            Duration triggerWindow,
            int batchSize,
            int maximumAttempts) {
        public Settings {
            requireText(jobCode, "jobCode");
            requireText(jobVersion, "jobVersion");
            requireText(jobContentHash, "jobContentHash");
            requireText(runtimePolicyVersion, "runtimePolicyVersion");
            requireText(workerId, "workerId");
            requirePositive(leaseDuration, "leaseDuration");
            requirePositive(retryDelay, "retryDelay");
            requirePositive(discoveryOverlap, "discoveryOverlap");
            requirePositive(triggerWindow, "triggerWindow");
            if (triggerWindow.toMillis() < 1000) {
                throw new IllegalArgumentException("triggerWindow must be at least one second");
            }
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("batchSize must be between 1 and 1000");
            }
            if (maximumAttempts < 1) {
                throw new IllegalArgumentException("maximumAttempts must be positive");
            }
        }
    }

    public record RunReport(
            UUID runId,
            UUID correlationId,
            String triggerId,
            boolean duplicateTrigger,
            int discovered,
            int claimed,
            int succeeded,
            int alreadyApplied,
            int retries,
            int quarantined,
            int categoryFailures,
            String terminalStatus) {}

    private final DurableBatchStore store;
    private final Map<BatchCategory, BatchCategoryPort> ports;
    private final Consumer<RunReport> evidence;
    private final Settings settings;

    public DurableDeadlineBatchRuntime(
            DurableBatchStore store,
            List<BatchCategoryPort> ports,
            Consumer<RunReport> evidence,
            Settings settings) {
        this.store = Objects.requireNonNull(store, "store");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ports = new EnumMap<>(BatchCategory.class);
        for (BatchCategoryPort port : ports) {
            Objects.requireNonNull(port, "port");
            if (this.ports.putIfAbsent(port.category(), port) != null) {
                throw new IllegalArgumentException("duplicate category port: " + port.category());
            }
        }
        Set<BatchCategory> required = Set.of(BatchCategory.values());
        if (!this.ports.keySet().equals(required)) {
            throw new IllegalStateException("all approved A21 category adapters must be registered");
        }
    }

    public void initialize() {
        store.publishJobVersion(
                settings.jobCode(), settings.jobVersion(), CATEGORY_SET_DOCUMENT,
                settings.jobContentHash());
    }

    public RunReport run() {
        Instant databaseNow = store.databaseNow();
        Instant windowEnd = floor(databaseNow, settings.triggerWindow());
        Instant windowStart = windowEnd.minus(settings.discoveryOverlap());
        String triggerId = settings.jobCode() + ":" + settings.jobVersion() + ":" + windowEnd;
        UUID correlationId = UUID.randomUUID();
        UUID runId = store.startRun(
                settings.jobCode(), settings.jobVersion(), settings.runtimePolicyVersion(),
                triggerId, windowStart, windowEnd);
        if (!"RUNNING".equals(store.runStatus(runId))) {
            return new RunReport(runId, correlationId, triggerId, true,
                    0, 0, 0, 0, 0, 0, 0, store.runStatus(runId));
        }

        MutableReport totals = new MutableReport();
        for (String categoryCode : REGISTERED_CATEGORIES) {
            runCategory(runId, correlationId, categoryCode, totals);
        }
        String terminalStatus = totals.categoryFailures == 0
                        && totals.retries == 0 && totals.quarantined == 0
                ? "SUCCEEDED"
                : totals.succeeded + totals.alreadyApplied > 0 ? "PARTIAL_FAILED" : "FAILED";
        RunReport report = new RunReport(
                runId, correlationId, triggerId, false,
                totals.discovered, totals.claimed, totals.succeeded, totals.alreadyApplied,
                totals.retries, totals.quarantined, totals.categoryFailures, terminalStatus);
        evidence.accept(report);
        store.completeRun(runId, terminalStatus);
        return report;
    }

    private void runCategory(
            UUID runId, UUID correlationId, String categoryCode, MutableReport totals) {
        BatchCategoryPort port = portFor(categoryCode);
        Map<UUID, WorkItem> currentCandidates = new HashMap<>();
        try {
            var page = port.claimDue(new ClaimRequest(
                    settings.workerId(), settings.runtimePolicyVersion(), settings.leaseDuration(),
                    null, settings.batchSize(), runId, correlationId));
            List<WorkItem> candidates = page.items().stream()
                    .filter(item -> categoryCode.equals(categoryCode(item)))
                    .toList();
            if (candidates.size() > settings.batchSize()) {
                throw new IllegalStateException("CATEGORY_RETURNED_UNBOUNDED_PAGE");
            }
            Set<UUID> discovered = new HashSet<>();
            String lastSourceKey = null;
            Instant lastDueAt = null;
            for (WorkItem candidate : candidates) {
                String sourceKey = sha256(candidate.idempotencyKey());
                String sourceVersion = candidate.itemId();
                if (sourceVersion.length() > 160) {
                    throw new IllegalStateException("CATEGORY_SOURCE_VERSION_TOO_LONG");
                }
                UUID itemId = store.discover(
                        runId, categoryCode, sourceKey, sourceVersion,
                        candidate.dueAt(), correlationId);
                currentCandidates.put(itemId, candidate);
                discovered.add(itemId);
                lastSourceKey = sourceKey;
                lastDueAt = candidate.dueAt();
            }
            totals.discovered += discovered.size();
            if (lastSourceKey != null) {
                store.saveCheckpoint(
                        settings.jobCode(), settings.jobVersion(), categoryCode, "default",
                        lastDueAt, lastSourceKey, runId, candidates.size());
            }
        } catch (RuntimeException failure) {
            totals.categoryFailures++;
            return;
        }

        List<ClaimedItem> claims;
        try {
            claims = store.claimDue(
                    categoryCode, settings.workerId(), settings.runtimePolicyVersion(),
                    settings.leaseDuration(), settings.batchSize());
        } catch (RuntimeException failure) {
            totals.categoryFailures++;
            return;
        }
        totals.claimed += claims.size();
        for (ClaimedItem claim : claims) {
            WorkItem item = currentCandidates.getOrDefault(claim.itemId(), reconstruct(port, claim));
            ItemResult result;
            try {
                result = Objects.requireNonNull(
                        port.execute(item, runId, claim.correlationId()), "item result");
            } catch (RuntimeException failure) {
                result = ItemResult.retryable("UNCLASSIFIED_EXECUTION_FAILURE");
            }
            finalizeClaim(claim, result, totals);
        }
    }

    private void finalizeClaim(ClaimedItem claim, ItemResult result, MutableReport totals) {
        if (result.status() == ItemStatus.COMPLETED) {
            store.succeed(claim.itemId(), claim.claimToken(), "APPLIED");
            totals.succeeded++;
        } else if (result.status() == ItemStatus.ALREADY_COMPLETED) {
            store.succeed(claim.itemId(), claim.claimToken(), "ALREADY_APPLIED");
            totals.alreadyApplied++;
        } else if (result.status() == ItemStatus.RETRYABLE_FAILURE
                && claim.attemptNumber() < settings.maximumAttempts()) {
            store.retry(claim.itemId(), claim.claimToken(), result.failureCode(),
                    store.databaseNow().plus(settings.retryDelay()));
            totals.retries++;
        } else {
            String failureCode = result.status() == ItemStatus.RETRYABLE_FAILURE
                    ? "BATCH_RETRY_EXHAUSTED" : result.failureCode();
            store.quarantine(claim.itemId(), claim.claimToken(), failureCode);
            totals.quarantined++;
        }
    }

    private BatchCategoryPort portFor(String categoryCode) {
        return ports.get(switch (categoryCode) {
            case "SESSION_EXPIRY" -> BatchCategory.SESSION;
            case "DELEGATED_CREDENTIAL_EXPIRY", "DELEGATED_AUTHORIZATION_EXPIRY" ->
                    BatchCategory.DELEGATED_TOKEN;
            case "SANCTION_EXPIRY" -> BatchCategory.SANCTION;
            case "NOTIFICATION_RETRY" -> BatchCategory.NOTIFICATION;
            case "CASE_RESPONSE_DEADLINE" -> BatchCategory.CASE_DEADLINE;
            default -> throw new IllegalStateException("unknown approved category");
        });
    }

    private static String categoryCode(WorkItem item) {
        return switch (item.category()) {
            case SESSION -> "SESSION_EXPIRY";
            case SANCTION -> "SANCTION_EXPIRY";
            case NOTIFICATION -> "NOTIFICATION_RETRY";
            case CASE_DEADLINE -> "CASE_RESPONSE_DEADLINE";
            case DELEGATED_TOKEN -> item.itemId().startsWith("AUTHORIZATION|")
                    ? "DELEGATED_AUTHORIZATION_EXPIRY" : "DELEGATED_CREDENTIAL_EXPIRY";
        };
    }

    private static WorkItem reconstruct(BatchCategoryPort port, ClaimedItem claim) {
        return new WorkItem(
                port.category(), claim.sourceVersion(), claim.dueAt(),
                "batch-item:" + claim.sourceKey(), claim.claimToken(), claim.attemptNumber());
    }

    private static Instant floor(Instant instant, Duration window) {
        long millis = window.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(instant.toEpochMilli(), millis) * millis);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static final class MutableReport {
        private int discovered;
        private int claimed;
        private int succeeded;
        private int alreadyApplied;
        private int retries;
        private int quarantined;
        private int categoryFailures;
    }
}
