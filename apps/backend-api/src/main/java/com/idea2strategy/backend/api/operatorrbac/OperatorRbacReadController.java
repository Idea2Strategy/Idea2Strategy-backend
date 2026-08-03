package com.idea2strategy.backend.api.operatorrbac;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadRejectedException;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadResult;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/operations")
@ConditionalOnProperty(
        prefix = "idea2strategy.operator-rbac.read-guard",
        name = "enabled", havingValue = "true")
public class OperatorRbacReadController {
    private final OperatorRbacReadService service;
    private final CurrentOperatorRbacContext securityContext;

    public OperatorRbacReadController(
            OperatorRbacReadService service, CurrentOperatorRbacContext securityContext) {
        this.service = service;
        this.securityContext = securityContext;
    }

    @GetMapping("/me")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public OperatorRbacReadResult.Self me(HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        return service.readSelf(current(correlationId), correlationId);
    }

    @GetMapping("/rbac/catalog")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public OperatorRbacReadResult.Catalog catalog(HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        return service.readCatalog(current(correlationId), correlationId);
    }

    @GetMapping("/rbac/operators/{operatorId}/assignments")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public OperatorRbacReadResult.Assignments assignments(
            @PathVariable UUID operatorId, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        return service.readAssignments(current(correlationId), operatorId, correlationId);
    }

    private com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext current(
            UUID correlationId) {
        return securityContext.current()
                .filter(context -> context.trustedExternalSubject())
                .orElseThrow(() -> new OperatorRbacReadRejectedException(
                        OperatorRbacReadRejectedException.Reason.UNAUTHENTICATED,
                        "OPERATOR_AUTHENTICATION_REQUIRED", correlationId));
    }

    private static UUID correlationId(HttpServletRequest request) {
        String value = request.getHeader("X-Correlation-Id");
        try {
            return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return UUID.randomUUID();
        }
    }
}
