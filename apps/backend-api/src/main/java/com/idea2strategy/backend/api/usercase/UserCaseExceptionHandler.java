package com.idea2strategy.backend.api.usercase;

import com.idea2strategy.backend.application.usercase.UserCaseRejectedException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserCaseController.class)
public class UserCaseExceptionHandler {
    @ExceptionHandler(UserCaseRejectedException.class)
    ProblemDetail rejected(UserCaseRejectedException exception) {
        HttpStatus status = "RESOURCE_NOT_AVAILABLE".equals(exception.code())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.code());
        problem.setType(URI.create("urn:idea2strategy:user-case:" + exception.code().toLowerCase()));
        problem.setTitle(exception.code());
        return problem;
    }
}
