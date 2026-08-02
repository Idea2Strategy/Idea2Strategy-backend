package com.idea2strategy.backend.api.sanction;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthenticationRejectedException;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommand;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionResult;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/accounts/{accountId}/sanctions")
@ConditionalOnBean({AccountSanctionCommandService.class, CurrentOperatorRbacContext.class})
public class AccountSanctionController {
    private final AccountSanctionCommandService commands;
    private final CurrentOperatorRbacContext securityContext;

    public AccountSanctionController(
            AccountSanctionCommandService commands, CurrentOperatorRbacContext securityContext) {
        this.commands = commands;
        this.securityContext = securityContext;
    }

    @PostMapping
    public Response apply(@PathVariable UUID accountId, @RequestBody ApplyRequest request) {
        return execute(new AccountSanctionCommand(
                AccountSanctionCommand.Type.APPLY, actor(), accountId, request.sanctionId(),
                request.type(), request.reasonCode(), request.expiresAt(), request.sourceCaseId(),
                request.correlationId(), request.idempotencyKey(), request.requestHash(),
                request.expectedVersion()));
    }

    @PostMapping("/{sanctionId}:lift")
    public Response lift(
            @PathVariable UUID accountId,
            @PathVariable UUID sanctionId,
            @RequestBody LiftRequest request) {
        return execute(new AccountSanctionCommand(
                AccountSanctionCommand.Type.LIFT, actor(), accountId, sanctionId, null,
                request.reasonCode(), null, null, request.correlationId(),
                request.idempotencyKey(), request.requestHash(), request.expectedVersion()));
    }

    private OperatorRequestContext actor() {
        return securityContext.current()
                .filter(OperatorRequestContext::trustedExternalSubject)
                .orElseThrow(AccountSanctionAuthenticationRejectedException::new);
    }

    private Response execute(AccountSanctionCommand command) {
        AccountSanctionResult result = commands.execute(command);
        if (result.status() == AccountSanctionResult.Status.REJECTED) {
            throw new AccountSanctionRejectedException(result.code(), command.correlationId());
        }
        return new Response(result.code(), command.sanctionId(), command.correlationId(),
                result.mutation() == null ? command.expectedVersion() : result.mutation().newVersion());
    }

    public record ApplyRequest(
            UUID sanctionId,
            AccountSanctionState.Type type,
            String reasonCode,
            Instant expiresAt,
            UUID sourceCaseId,
            UUID correlationId,
            String idempotencyKey,
            String requestHash,
            long expectedVersion) {}

    public record LiftRequest(
            String reasonCode,
            UUID correlationId,
            String idempotencyKey,
            String requestHash,
            long expectedVersion) {}

    public record Response(String code, UUID sanctionReference, UUID correlationId, long aggregateVersion) {}
}
