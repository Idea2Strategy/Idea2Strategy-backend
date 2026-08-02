package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.AccountPreferencesNotFoundException;
import com.idea2strategy.backend.application.identity.DuplicateEmailException;
import com.idea2strategy.backend.application.identity.PasswordPolicyException;
import com.idea2strategy.backend.application.identity.PasswordResetRejectedException;
import com.idea2strategy.backend.application.identity.PolicyDecisionRejectedException;
import com.idea2strategy.backend.application.identity.VerificationRejectedException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityAuthExceptionHandler {
    @ExceptionHandler(AccountPreferencesNotFoundException.class)
    ResponseEntity<Map<String, String>> preferencesNotFound(AccountPreferencesNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "ACCOUNT_PREFERENCES_NOT_FOUND"));
    }

    @ExceptionHandler(PolicyDecisionRejectedException.class)
    ResponseEntity<Map<String, String>> policyNotCurrent(PolicyDecisionRejectedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "POLICY_NOT_CURRENT"));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateEmailException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "EMAIL_ALREADY_REGISTERED"));
    }

    @ExceptionHandler(AuthenticationRejectedException.class)
    ResponseEntity<Map<String, String>> authentication(AuthenticationRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "AUTHENTICATION_REJECTED"));
    }

    @ExceptionHandler({PasswordPolicyException.class, PasswordResetRejectedException.class, VerificationRejectedException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> invalid(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_AUTHENTICATION_REQUEST"));
    }
}
