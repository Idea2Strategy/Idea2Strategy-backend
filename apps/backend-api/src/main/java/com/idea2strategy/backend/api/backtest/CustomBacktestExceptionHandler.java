package com.idea2strategy.backend.api.backtest;

import com.idea2strategy.backend.application.backtest.BacktestRequestIdempotencyConflictException;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CustomBacktestController.class)
public class CustomBacktestExceptionHandler {
    @ExceptionHandler(BacktestRequestIdempotencyConflictException.class)
    ProblemDetail conflict(BacktestRequestIdempotencyConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail missing(NoSuchElementException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ProblemDetail rejected(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }

    @ExceptionHandler(ImmutableStrategyReleaseRejectedException.class)
    ProblemDetail officialInputsUnavailable(ImmutableStrategyReleaseRejectedException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        detail.setProperty("reasonCode", "OFFICIAL_BACKTEST_INPUTS_UNAVAILABLE");
        return detail;
    }
}
