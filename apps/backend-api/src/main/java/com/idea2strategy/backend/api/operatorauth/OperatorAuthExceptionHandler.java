package com.idea2strategy.backend.api.operatorauth;

import com.idea2strategy.backend.operatortrust.OperatorAuthenticationRejectedException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OperatorAuthController.class)
public class OperatorAuthExceptionHandler {
    @ExceptionHandler(OperatorAuthenticationRejectedException.class)
    ResponseEntity<Map<String, Object>> rejected(OperatorAuthenticationRejectedException failure) {
        HttpStatus status = "OPERATOR_AUTHENTICATION_RATE_LIMITED".equals(failure.code())
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED;
        String publicCode = status == HttpStatus.TOO_MANY_REQUESTS
                ? failure.code() : "OPERATOR_AUTHENTICATION_REJECTED";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of(
                "code", publicCode, "correlationId", UUID.randomUUID().toString()));
    }
}
