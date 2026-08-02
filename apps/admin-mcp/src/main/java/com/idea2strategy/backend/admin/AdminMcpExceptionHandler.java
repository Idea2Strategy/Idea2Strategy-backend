package com.idea2strategy.backend.admin;

import com.idea2strategy.backend.application.adminmcp.AdminMcpAuthenticationRejectedException;
import com.idea2strategy.backend.application.adminmcp.AdminMcpIdempotencyConflictException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminMcpController.class)
public class AdminMcpExceptionHandler {
    @ExceptionHandler(AdminMcpAuthenticationRejectedException.class)
    ProblemDetail authentication(AdminMcpAuthenticationRejectedException exception) {
        return problem(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(AdminMcpIdempotencyConflictException.class)
    ProblemDetail idempotency(AdminMcpIdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    ProblemDetail invalidRequest(RuntimeException exception) {
        return problem(HttpStatus.BAD_REQUEST, "ADMIN_MCP_REQUEST_INVALID");
    }

    private static ProblemDetail problem(HttpStatus status, String code) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, code);
        detail.setTitle(code);
        detail.setType(URI.create("urn:idea2strategy:admin-mcp:" + code.toLowerCase()));
        return detail;
    }
}
