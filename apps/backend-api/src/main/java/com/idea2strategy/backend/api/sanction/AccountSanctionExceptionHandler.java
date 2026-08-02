package com.idea2strategy.backend.api.sanction;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthenticationRejectedException;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionIdempotencyConflictException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AccountSanctionController.class)
public class AccountSanctionExceptionHandler {
    @ExceptionHandler(AccountSanctionAuthenticationRejectedException.class)
    ProblemDetail authentication(AccountSanctionAuthenticationRejectedException exception) {
        return problem(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(AccountSanctionIdempotencyConflictException.class)
    ProblemDetail idempotency(AccountSanctionIdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(AccountSanctionRejectedException.class)
    ProblemDetail rejected(AccountSanctionRejectedException exception) {
        HttpStatus status = exception.getMessage().endsWith("NOT_FOUND")
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        ProblemDetail detail = problem(status, exception.getMessage());
        detail.setProperty("correlationId", exception.correlationId());
        return detail;
    }

    private static ProblemDetail problem(HttpStatus status, String code) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, code);
        detail.setTitle(code);
        detail.setType(URI.create("urn:idea2strategy:account-sanction:" + code.toLowerCase()));
        return detail;
    }
}
