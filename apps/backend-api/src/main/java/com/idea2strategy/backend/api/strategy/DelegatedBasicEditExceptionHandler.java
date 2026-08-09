package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.DelegatedBasicEditPreviewMismatchException;
import com.idea2strategy.backend.application.strategy.DelegatedBasicEditRejectedException;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScopeDeniedException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Refusals for the delegated edit route only.
 *
 * <p>Kept separate from {@link StrategyAuthoringExceptionHandler} because these responses carry a
 * machine-readable {@code code}: the external tool contract promises stable reasons, not only
 * stable HTTP statuses, and an AI tool decides whether to stop, re-preview, or ask the user from
 * that field. Owner-facing authoring endpoints make no such promise, and widening the existing
 * advice would extend one to them by accident.
 */
@RestControllerAdvice(assignableTypes = DelegatedBasicEditController.class)
public class DelegatedBasicEditExceptionHandler {
    @ExceptionHandler(DelegatedStrategyScopeDeniedException.class)
    ProblemDetail scopeDenied(DelegatedStrategyScopeDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "SCOPE_DENIED", "Delegated scope denied", exception);
    }

    @ExceptionHandler(DelegatedBasicEditPreviewMismatchException.class)
    ProblemDetail previewMismatch(DelegatedBasicEditPreviewMismatchException exception) {
        return problem(HttpStatus.CONFLICT, "PREVIEW_MISMATCH", "Reviewed preview mismatch", exception);
    }

    @ExceptionHandler(DelegatedBasicEditRejectedException.class)
    ProblemDetail editRejected(DelegatedBasicEditRejectedException exception) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY, "EDIT_REJECTED", "Delegated edit rejected", exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid delegated edit", exception);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "STRATEGY_NOT_FOUND", "Strategy not found", exception);
    }

    private static ProblemDetail problem(
            HttpStatus status, String code, String title, RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("message", exception.getMessage());
        return problem;
    }
}
