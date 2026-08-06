package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.AccountPreferencesService;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import com.idea2strategy.backend.application.identity.UpdateAccountPreferences;
import com.idea2strategy.backend.domain.identity.AccountPreferences;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account/preferences")
@ConditionalOnBean({AccountPreferencesService.class, SessionManagementService.class, HmacSessionTokens.class})
public class IdentityPreferencesController {
    private final AccountPreferencesService preferences;
    private final CustomerAccessPrincipal principal;

    public IdentityPreferencesController(
            AccountPreferencesService preferences,
            CustomerAccessPrincipal principal) {
        this.preferences = preferences;
        this.principal = principal;
    }

    @GetMapping
    public AccountPreferences get(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return preferences.get(principal.accountId());
    }

    @PatchMapping
    public AccountPreferences update(
            @RequestBody UpdatePreferencesRequest request,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        return preferences.update(
                principal.accountId(),
                new UpdateAccountPreferences(
                        request.languageCode(),
                        request.timezoneName(),
                        request.themePreference(),
                        correlation));
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record UpdatePreferencesRequest(String languageCode, String timezoneName, String themePreference) {}
}
