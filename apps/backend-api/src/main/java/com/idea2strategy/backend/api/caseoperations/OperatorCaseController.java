package com.idea2strategy.backend.api.caseoperations;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseApiGuardCatalog;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseAuthenticationRejectedException;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommand;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommandService;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseDecisionResult;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseDetail;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueryService;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueuePort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueueRequest;
import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/cases")
@ConditionalOnProperty(
        prefix = "idea2strategy.operator-case.guard",
        name = {"queue-permission-id", "detail-permission-id"})
public class OperatorCaseController {
    private final OperatorCaseCommandService commands;
    private final OperatorCaseQueryService queries;
    private final CurrentOperatorRbacContext context;
    private final OperatorCaseApiGuardCatalog guards;

    public OperatorCaseController(
            OperatorCaseCommandService commands,
            OperatorCaseQueryService queries,
            CurrentOperatorRbacContext context,
            OperatorCaseApiGuardCatalog guards) {
        this.commands = commands;
        this.queries = queries;
        this.context = context;
        this.guards = guards;
    }

    @GetMapping
    public OperatorCaseQueuePort.Page queue(
            @RequestParam Set<UserCaseType> type,
            @RequestParam(required = false, defaultValue = "") Set<UserCaseStatus> status,
            @RequestParam(required = false) UUID assigneeOperatorId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return queries.queue(new OperatorCaseQueueRequest(
                current(), guards.activeGuard().queuePermissionId(), type, status,
                assigneeOperatorId, cursor, limit));
    }

    @GetMapping("/{caseId}")
    public OperatorCaseDetail detail(@PathVariable UUID caseId) {
        return queries.detail(current(), guards.activeGuard().detailPermissionId(), caseId);
    }

    @PostMapping("/{caseId}/commands/{action}")
    public CommandResponse command(
            @PathVariable UUID caseId,
            @PathVariable OperatorCaseCommand.Action action,
            @RequestBody CommandRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID() : UUID.fromString(correlationId);
        List<UUID> evidence = request.evidenceIds() == null ? List.of() : List.copyOf(request.evidenceIds());
        OperatorCaseCommand command = new OperatorCaseCommand(
                action, current(), caseId, request.expectedVersion(), request.assigneeOperatorId(),
                guards.activeGuard().permissionFor(action), request.reasonCode(), evidence,
                request.sanctionId(), request.sanctionType(), request.sanctionExpiresAt(),
                request.expectedSanctionVersion(), correlation, idempotencyKey,
                hash(action, caseId, request, evidence));
        OperatorCaseDecisionResult result = commands.execute(command);
        if (result.status() == OperatorCaseDecisionResult.Status.REJECTED) {
            throw new OperatorCaseRejectedException(
                    result.code(), correlation, rejectedStatus(result.code()));
        }
        return new CommandResponse(
                result.status(), result.code(), correlation,
                result.mutation() == null ? request.expectedVersion() : result.mutation().nextVersion());
    }

    private OperatorRequestContext current() {
        return context.current().filter(OperatorRequestContext::trustedExternalSubject)
                .orElseThrow(OperatorCaseAuthenticationRejectedException::new);
    }

    private static String hash(
            OperatorCaseCommand.Action action, UUID caseId, CommandRequest request, List<UUID> evidence) {
        String material = String.join("\n",
                action.name(), caseId.toString(), Long.toString(request.expectedVersion()),
                Objects.toString(request.assigneeOperatorId(), ""), request.reasonCode(),
                String.join(",", new TreeSet<>(evidence.stream().map(UUID::toString).toList())),
                Objects.toString(request.sanctionId(), ""),
                Objects.toString(request.sanctionType(), ""),
                Objects.toString(request.sanctionExpiresAt(), ""),
                Long.toString(request.expectedSanctionVersion()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static int rejectedStatus(String code) {
        if (code.contains("PERMISSION") || code.contains("MFA")) return 403;
        if (code.contains("STALE") || code.contains("ALREADY")) return 409;
        if (code.contains("NOT_AVAILABLE")) return 404;
        return 422;
    }

    public record CommandRequest(
            long expectedVersion,
            UUID assigneeOperatorId,
            String reasonCode,
            List<UUID> evidenceIds,
            UUID sanctionId,
            AccountSanctionState.Type sanctionType,
            Instant sanctionExpiresAt,
            long expectedSanctionVersion) {}

    public record CommandResponse(
            OperatorCaseDecisionResult.Status status,
            String code,
            UUID correlationId,
            long caseVersion) {}
}
