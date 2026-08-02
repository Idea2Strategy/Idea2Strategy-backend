package com.idea2strategy.backend.api.caseoperations;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommand;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionResult;
import com.idea2strategy.backend.application.caseoperations.CaseSanctionCommandPort;
import org.springframework.beans.factory.ObjectProvider;

final class AccountSanctionCaseCommandAdapter implements CaseSanctionCommandPort {
    private final ObjectProvider<AccountSanctionCommandService> services;

    AccountSanctionCaseCommandAdapter(ObjectProvider<AccountSanctionCommandService> services) {
        this.services = services;
    }

    @Override
    public Result execute(Request request) {
        AccountSanctionCommandService service = services.getIfAvailable();
        if (service == null) {
            return new Result(Result.Status.UNKNOWN, "SANCTION_PROVIDER_NOT_INTEGRATED", null);
        }
        AccountSanctionCommand.Type type = request.operation() == Operation.APPLY
                ? AccountSanctionCommand.Type.APPLY
                : AccountSanctionCommand.Type.LIFT;
        AccountSanctionResult result = service.execute(new AccountSanctionCommand(
                type,
                request.requestContext(),
                request.accountId(),
                request.sanctionId(),
                request.sanctionType(),
                request.reasonCode(),
                request.expiresAt(),
                request.caseId(),
                request.correlationId(),
                "operator-case:" + request.idempotencyKey(),
                request.requestHash(),
                request.expectedSanctionVersion()));
        if (result.status() == AccountSanctionResult.Status.REJECTED) {
            return new Result(Result.Status.REJECTED, result.code(), null);
        }
        if (result.status() == AccountSanctionResult.Status.NO_OP) {
            return new Result(Result.Status.REJECTED, result.code(), null);
        }
        return new Result(
                Result.Status.APPLIED,
                result.code(),
                result.mutation().sanctionId() + ":" + result.mutation().newVersion());
    }
}
