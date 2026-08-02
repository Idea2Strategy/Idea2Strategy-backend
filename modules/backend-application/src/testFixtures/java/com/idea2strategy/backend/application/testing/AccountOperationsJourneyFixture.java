package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommand;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationResult;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class AccountOperationsJourneyFixture {
    public static final UUID ACCOUNT_ID = uuid(1);
    public static final UUID OPERATOR_ID = uuid(2);
    public static final UUID CASE_ID = uuid(3);
    public static final UUID SANCTION_ID = uuid(4);
    public static final UUID STRATEGY_INCIDENT_ID = uuid(11);
    public static final UUID BOT_INCIDENT_ID = uuid(12);
    public static final UUID ROOM_INCIDENT_ID = uuid(13);

    private final Clock clock;
    private final DelegatedAuthorizationService delegatedAuthorizationService;
    private final DeadlineBatchOrchestrator deadlineBatchOrchestrator;
    private final Map<String, Receipt> receipts = new HashMap<>();
    private final Map<String, OutboxMessage> outbox = new LinkedHashMap<>();
    private final List<Trace> traces = new ArrayList<>();
    private final List<Audit> audits = new ArrayList<>();
    private final Map<UUID, FakeIncident> incidents = new LinkedHashMap<>();
    private final Map<String, String> preferences = new LinkedHashMap<>();
    private final Set<UUID> operatorPermissions;

    private AccountStatus accountStatus;
    private boolean emailVerified;
    private boolean sessionActive;
    private boolean operatorMfa;
    private boolean sanctioned;
    private CaseStatus caseStatus;
    private UUID caseOwnerAccountId;
    private UUID caseAssigneeOperatorId;
    private long caseVersion;
    private int deliveredNotifications;

    public AccountOperationsJourneyFixture(
            Clock clock,
            Set<UUID> operatorPermissions,
            DelegatedAuthorizationService delegatedAuthorizationService,
            DeadlineBatchOrchestrator deadlineBatchOrchestrator) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.operatorPermissions = Set.copyOf(operatorPermissions);
        this.delegatedAuthorizationService = Objects.requireNonNull(
                delegatedAuthorizationService, "delegatedAuthorizationService");
        this.deadlineBatchOrchestrator = Objects.requireNonNull(
                deadlineBatchOrchestrator, "deadlineBatchOrchestrator");
    }

    public String registerAndVerify(String key, String requestHash, UUID correlationId) {
        return idempotent(key, requestHash, () -> {
            accountStatus = AccountStatus.ACTIVE;
            emailVerified = true;
            trace(correlationId, "API", "SIGNUP");
            trace(correlationId, "DB", "ACCOUNT_ACTIVE");
            audit(correlationId, "ACCOUNT_REGISTERED", "NONE", "ACTIVE");
            return "ACCOUNT_ACTIVE";
        });
    }

    public String login(String key, String requestHash, UUID correlationId) {
        return idempotent(key, requestHash, () -> {
            require(accountStatus == AccountStatus.ACTIVE && emailVerified && !sanctioned, "AUTHENTICATION_REJECTED");
            sessionActive = true;
            trace(correlationId, "API", "LOGIN");
            trace(correlationId, "DB", "SESSION_ACTIVE");
            audit(correlationId, "SESSION_STARTED", "INACTIVE", "ACTIVE");
            return "SESSION_ACTIVE";
        });
    }

    public String updatePreferences(
            String language,
            String timezone,
            String theme,
            String key,
            String requestHash,
            UUID correlationId) {
        return idempotent(key, requestHash, () -> {
            require(sessionActive, "AUTHENTICATION_REQUIRED");
            preferences.put("language", language);
            preferences.put("timezone", timezone);
            preferences.put("theme", theme);
            trace(correlationId, "API", "PREFERENCES_UPDATE");
            trace(correlationId, "DB", "PREFERENCES_SAVED");
            audit(correlationId, "PREFERENCES_UPDATED", "DEFAULT", theme);
            return "PREFERENCES_UPDATED";
        });
    }

    public void activateOperatorMfa() {
        operatorMfa = true;
    }

    public FakeIncident recordFakeIncident(
            FakeDomain domain,
            UUID incidentId,
            long version,
            String summaryCode,
            UUID correlationId) {
        var incident = new FakeIncident(incidentId, domain, version, summaryCode, clock.instant());
        incidents.put(incidentId, incident);
        trace(correlationId, "FAKE_PROVIDER", domain.name() + "_INCIDENT_V" + version);
        return incident;
    }

    public String invokeOperatorTool(
            UUID permissionId,
            FakeDomain domain,
            UUID incidentId,
            long expectedVersion,
            UUID correlationId) {
        require(operatorMfa && operatorPermissions.contains(permissionId), "RESOURCE_NOT_AVAILABLE");
        FakeIncident incident = requireIncident(incidentId);
        require(incident.domain() == domain && incident.version() == expectedVersion, "RESOURCE_NOT_AVAILABLE");
        trace(correlationId, "MCP", domain.name() + "_QUERY");
        audit(correlationId, "MCP_TOOL_ALLOWED", domain.name(), incident.summaryCode());
        return incident.summaryCode();
    }

    public String submitCase(
            UUID ownerAccountId,
            UUID incidentId,
            String key,
            String requestHash,
            UUID correlationId) {
        return idempotent(key, requestHash, () -> {
            require(ownerAccountId.equals(ACCOUNT_ID) && sessionActive, "RESOURCE_NOT_AVAILABLE");
            FakeIncident incident = requireIncident(incidentId);
            caseOwnerAccountId = ownerAccountId;
            caseStatus = CaseStatus.OPEN;
            caseVersion = 1;
            trace(correlationId, "API", "CASE_SUBMIT");
            trace(correlationId, "DB", "CASE_OPEN");
            stageOutbox(correlationId, "CASE_SUBMITTED", incident.summaryCode(), caseVersion);
            audit(correlationId, "CASE_SUBMITTED", "NONE", "OPEN");
            return "CASE_OPEN";
        });
    }

    public String assignAndStartReview(
            UUID permissionId,
            String key,
            String requestHash,
            UUID correlationId) {
        return idempotent(key, requestHash, () -> {
            require(operatorMfa && operatorPermissions.contains(permissionId), "RESOURCE_NOT_AVAILABLE");
            require(caseStatus == CaseStatus.OPEN, "CASE_TRANSITION_NOT_ALLOWED");
            caseAssigneeOperatorId = OPERATOR_ID;
            caseStatus = CaseStatus.UNDER_REVIEW;
            caseVersion++;
            trace(correlationId, "API", "CASE_ASSIGN_AND_REVIEW");
            trace(correlationId, "DB", "CASE_UNDER_REVIEW");
            audit(correlationId, "CASE_REVIEW_STARTED", "OPEN", "UNDER_REVIEW");
            return "CASE_UNDER_REVIEW";
        });
    }

    public String applySanctionAndResolveCase(
            UUID permissionId,
            String key,
            String requestHash,
            UUID correlationId) {
        return idempotent(key, requestHash, () -> {
            require(operatorMfa && operatorPermissions.contains(permissionId), "RESOURCE_NOT_AVAILABLE");
            require(caseStatus == CaseStatus.UNDER_REVIEW && OPERATOR_ID.equals(caseAssigneeOperatorId),
                    "CASE_TRANSITION_NOT_ALLOWED");
            sanctioned = true;
            sessionActive = false;
            caseStatus = CaseStatus.RESOLVED;
            caseVersion++;
            trace(correlationId, "API", "SANCTION_APPLY");
            trace(correlationId, "DB", "SANCTION_ACTIVE");
            stageOutbox(correlationId, "ACCOUNT_SANCTION_APPLIED", "SANCTION_ACTIVE", caseVersion);
            stageOutbox(correlationId, "CASE_RESOLVED", "RESOLVED", caseVersion);
            audit(correlationId, "SANCTION_APPLIED", "ACTIVE", "BLOCKED");
            audit(correlationId, "CASE_RESOLVED", "UNDER_REVIEW", "RESOLVED");
            return "CASE_RESOLVED_WITH_SANCTION";
        });
    }

    public String liftSanction(UUID correlationId) {
        require(sanctioned, "SANCTION_NOT_FOUND");
        sanctioned = false;
        trace(correlationId, "DB", "SANCTION_LIFTED");
        stageOutbox(correlationId, "ACCOUNT_SANCTION_LIFTED", "SANCTION_LIFTED", caseVersion);
        audit(correlationId, "SANCTION_LIFTED", "BLOCKED", "ACTIVE");
        return "SANCTION_LIFTED";
    }

    public DeliveryResult deliverNextNotification(boolean providerAvailable, UUID correlationId) {
        OutboxMessage next = outbox.values().stream().filter(message -> !message.delivered()).findFirst().orElse(null);
        if (next == null) {
            return new DeliveryResult("NO_MESSAGE", deliveredNotifications);
        }
        trace(correlationId, "WORKER", "OUTBOX_CLAIMED");
        if (!providerAvailable) {
            next.attempts++;
            trace(correlationId, "WORKER", "NOTIFICATION_RETRY_SCHEDULED");
            audit(correlationId, "NOTIFICATION_DELIVERY_FAILED", "PENDING", "PENDING");
            return new DeliveryResult("RETRY_SCHEDULED", deliveredNotifications);
        }
        next.attempts++;
        next.delivered = true;
        deliveredNotifications++;
        trace(correlationId, "WORKER", "NOTIFICATION_DELIVERED");
        audit(correlationId, "NOTIFICATION_DELIVERED", "PENDING", "DELIVERED");
        return new DeliveryResult("DELIVERED", deliveredNotifications);
    }

    public String closeAccount(String key, String requestHash, UUID correlationId) {
        return idempotent(key, requestHash, () -> {
            require(!sanctioned && sessionActive, "ACCOUNT_CLOSURE_BLOCKED");
            accountStatus = AccountStatus.CLOSED;
            sessionActive = false;
            trace(correlationId, "API", "WITHDRAWAL_REQUEST");
            trace(correlationId, "DB", "ACCOUNT_CLOSED");
            stageOutbox(correlationId, "ACCOUNT_CLOSED", "CLOSED", 1);
            audit(correlationId, "ACCOUNT_CLOSED", "ACTIVE", "CLOSED");
            return "ACCOUNT_CLOSED";
        });
    }

    public DelegatedAuthorizationResult authorizeDelegatedStrategy(DelegatedAuthorizationCommand command) {
        require(sessionActive, "AUTHENTICATION_REQUIRED");
        DelegatedAuthorizationResult result = delegatedAuthorizationService.execute(command);
        trace(command.correlationId(), "DB", "DELEGATED_AUTHORIZATION_APPLIED");
        audit(command.correlationId(), "DELEGATED_AUTHORIZATION_APPLIED", "NONE", result.status().name());
        return result;
    }

    public DeadlineBatchOrchestrator.RunSummary runDeadlineBatch(DeadlineBatchOrchestrator.RunCommand command) {
        require(operatorMfa, "OPERATOR_MFA_REQUIRED");
        DeadlineBatchOrchestrator.RunSummary summary = deadlineBatchOrchestrator.run(command);
        trace(command.correlationId(), "WORKER", "DEADLINE_BATCH_COMPLETED");
        audit(command.correlationId(), "DEADLINE_BATCH_COMPLETED", "PENDING", "RECORDED");
        return summary;
    }

    public String caseDetail(UUID accountId, UUID caseId) {
        require(ACCOUNT_ID.equals(accountId) && CASE_ID.equals(caseId) && accountId.equals(caseOwnerAccountId),
                "RESOURCE_NOT_AVAILABLE");
        return caseStatus.name();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                accountStatus,
                emailVerified,
                sessionActive,
                Map.copyOf(preferences),
                sanctioned,
                caseStatus,
                caseVersion,
                outbox.size(),
                deliveredNotifications,
                List.copyOf(traces),
                List.copyOf(audits));
    }

    private String idempotent(String key, String requestHash, Supplier<String> operation) {
        Receipt existing = receipts.get(key);
        if (existing != null) {
            require(existing.requestHash().equals(requestHash), "IDEMPOTENCY_CONFLICT");
            return existing.response();
        }
        String response = operation.get();
        receipts.put(key, new Receipt(requestHash, response));
        return response;
    }

    private FakeIncident requireIncident(UUID incidentId) {
        FakeIncident incident = incidents.get(incidentId);
        require(incident != null, "RESOURCE_NOT_AVAILABLE");
        return incident;
    }

    private void stageOutbox(UUID correlationId, String type, String summaryCode, long aggregateVersion) {
        String key = type + ":" + aggregateVersion;
        outbox.putIfAbsent(key, new OutboxMessage(key, type, correlationId, summaryCode));
        trace(correlationId, "OUTBOX", type);
    }

    private void trace(UUID correlationId, String stage, String action) {
        traces.add(new Trace(correlationId, stage, action, clock.instant()));
    }

    private void audit(UUID correlationId, String action, String before, String after) {
        audits.add(new Audit(correlationId, action, before, after, clock.instant()));
        trace(correlationId, "AUDIT", action);
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new JourneyRejectedException(code);
        }
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("40000000-0000-4000-8000-%012d".formatted(suffix));
    }

    public enum FakeDomain {
        STRATEGY,
        BOT,
        ROOM
    }

    public enum AccountStatus {
        ACTIVE,
        CLOSED
    }

    public enum CaseStatus {
        OPEN,
        UNDER_REVIEW,
        RESOLVED
    }

    public record FakeIncident(
            UUID id, FakeDomain domain, long version, String summaryCode, Instant occurredAt) {}

    public record DeliveryResult(String code, int deliveredCount) {}

    public record Trace(UUID correlationId, String stage, String action, Instant occurredAt) {}

    public record Audit(UUID correlationId, String action, String before, String after, Instant occurredAt) {}

    public record Snapshot(
            AccountStatus accountStatus,
            boolean emailVerified,
            boolean sessionActive,
            Map<String, String> preferences,
            boolean sanctioned,
            CaseStatus caseStatus,
            long caseVersion,
            int outboxMessageCount,
            int deliveredNotificationCount,
            List<Trace> traces,
            List<Audit> audits) {}

    private record Receipt(String requestHash, String response) {}

    private static final class OutboxMessage {
        private final String idempotencyKey;
        private final String type;
        private final UUID correlationId;
        private final String summaryCode;
        private int attempts;
        private boolean delivered;

        private OutboxMessage(String idempotencyKey, String type, UUID correlationId, String summaryCode) {
            this.idempotencyKey = idempotencyKey;
            this.type = type;
            this.correlationId = correlationId;
            this.summaryCode = summaryCode;
        }

        private boolean delivered() {
            return delivered;
        }
    }

    public static final class JourneyRejectedException extends RuntimeException {
        private final String code;

        public JourneyRejectedException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
