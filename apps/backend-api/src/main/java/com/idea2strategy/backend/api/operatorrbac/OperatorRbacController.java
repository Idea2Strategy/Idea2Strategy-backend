package com.idea2strategy.backend.api.operatorrbac;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacApiGuardCatalog;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacAuthenticationRejectedException;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommand;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandService;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacResult;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/rbac/assignments")
@ConditionalOnBean({OperatorRbacCommandService.class, CurrentOperatorRbacContext.class,
        OperatorRbacApiGuardCatalog.class})
public class OperatorRbacController {
    private final OperatorRbacCommandService service;
    private final CurrentOperatorRbacContext securityContext;
    private final OperatorRbacApiGuardCatalog guards;

    public OperatorRbacController(
            OperatorRbacCommandService service,
            CurrentOperatorRbacContext securityContext,
            OperatorRbacApiGuardCatalog guards) {
        this.service = service;
        this.securityContext = securityContext;
        this.guards = guards;
    }

    @PostMapping("/grants")
    public CommandResponse grant(@RequestBody GrantRequest request) {
        OperatorRequestContext actor = currentActor();
        var guard = guards.activeGuard();
        return execute(new OperatorRbacCommand(
                OperatorRbacCommand.Type.GRANT, actor, request.targetOperatorId(), request.roleId(), null,
                guard.grantPermissionId(), guard.catalogVersion(), request.expiresAt(), request.reasonCode(),
                request.correlationId(), request.idempotencyKey(), request.requestHash()));
    }

    @PostMapping("/revocations")
    public CommandResponse revoke(@RequestBody RevokeRequest request) {
        OperatorRequestContext actor = currentActor();
        var guard = guards.activeGuard();
        return execute(new OperatorRbacCommand(
                OperatorRbacCommand.Type.REVOKE, actor, request.targetOperatorId(), null,
                request.assignmentId(), guard.revokePermissionId(), guard.catalogVersion(), null,
                request.reasonCode(), request.correlationId(), request.idempotencyKey(), request.requestHash()));
    }

    private OperatorRequestContext currentActor() {
        return securityContext.current()
                .filter(OperatorRequestContext::trustedExternalSubject)
                .orElseThrow(OperatorRbacAuthenticationRejectedException::new);
    }

    private CommandResponse execute(OperatorRbacCommand command) {
        OperatorRbacResult result = service.execute(command);
        if (result.decisionStatus() == OperatorRbacResult.DecisionStatus.REJECTED) {
            int status = result.code().endsWith("NOT_FOUND") ? 404 : 403;
            throw new OperatorRbacRejectedException(result.code(), command.correlationId(), status);
        }
        return new CommandResponse(result.code(), command.correlationId(),
                result.mutation() == null ? null : result.mutation().assignmentId());
    }

    public record GrantRequest(
            UUID targetOperatorId, UUID roleId, Instant expiresAt, String reasonCode,
            UUID correlationId, String idempotencyKey, String requestHash) {}
    public record RevokeRequest(
            UUID targetOperatorId, UUID assignmentId, String reasonCode,
            UUID correlationId, String idempotencyKey, String requestHash) {}
    public record CommandResponse(String code, UUID correlationId, UUID assignmentId) {}
}
