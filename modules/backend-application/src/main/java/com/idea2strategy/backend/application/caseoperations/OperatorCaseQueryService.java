package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class OperatorCaseQueryService {
    private final OperatorCaseQueuePort cases;
    private final OperatorCaseAuthorizationPort authorization;
    private final OperatorEvidenceRedactor redactor;
    private final Clock clock;

    public OperatorCaseQueryService(
            OperatorCaseQueuePort cases,
            OperatorCaseAuthorizationPort authorization,
            OperatorEvidenceRedactor redactor,
            Clock clock) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OperatorCaseQueuePort.Page queue(OperatorCaseQueueRequest request) {
        Objects.requireNonNull(request, "request");
        requireTrusted(request.requestContext());
        Instant now = clock.instant();
        for (UserCaseType type : request.caseTypes()) {
            requireAuthorized(request.requestContext(), request.requiredPermissionId(), type, "QUEUE", now);
        }
        OperatorCaseQueuePort.Page page = cases.findQueue(new OperatorCaseQueuePort.Query(
                request.caseTypes(), request.statuses(), request.assigneeOperatorId(), request.cursor(), request.limit()), now);
        boolean scopeViolation = page.items().stream().anyMatch(item ->
                !request.caseTypes().contains(item.type())
                        || (!request.statuses().isEmpty() && !request.statuses().contains(item.status()))
                        || (request.assigneeOperatorId() != null
                                && !request.assigneeOperatorId().equals(item.assigneeOperatorId())));
        if (scopeViolation) {
            throw new IllegalStateException("CASE_QUEUE_SCOPE_VIOLATION");
        }
        return page;
    }

    public OperatorCaseDetail detail(
            OperatorRequestContext context,
            UUID requiredPermissionId,
            UUID caseId) {
        requireTrusted(context);
        Instant now = clock.instant();
        OperatorCaseState state = cases.findCase(caseId, now)
                .orElseThrow(() -> new OperatorCaseQueryRejectedException("CASE_NOT_AVAILABLE"));
        if (!state.caseView().id().equals(caseId)) {
            throw new IllegalStateException("CASE_QUEUE_SCOPE_VIOLATION");
        }
        requireAuthorized(context, requiredPermissionId, state.caseView().type(), "DETAIL", now);
        return new OperatorCaseDetail(
                state.caseView().id(),
                state.caseView().type(),
                state.caseView().status(),
                state.caseView().version(),
                state.assigneeOperatorId(),
                state.subject(),
                state.description(),
                redactor.redact(state.evidence()),
                state.caseView().updatedAt());
    }

    private void requireAuthorized(
            OperatorRequestContext context,
            UUID permissionId,
            UserCaseType type,
            String action,
            Instant now) {
        OperatorCaseAuthorizationPort.Decision decision = authorization.authorize(
                context, permissionId, type, action, now);
        if (!decision.granted()) {
            throw new OperatorCaseQueryRejectedException(decision.code());
        }
    }

    private static void requireTrusted(OperatorRequestContext context) {
        if (!context.sessionAuthenticated()) {
            throw new OperatorCaseAuthenticationRejectedException();
        }
    }
}
