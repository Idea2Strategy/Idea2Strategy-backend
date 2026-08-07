package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.OidcAuthenticationService;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerificationRequest;
import com.idea2strategy.backend.application.identity.OidcLoginCommand;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/oidc")
@ConditionalOnBean({OidcAuthenticationService.class, TrustedOidcIdTokenVerifier.class})
public class IdentityOidcLoginController {
    private final OidcAuthenticationService authentication;
    private final TrustedOidcIdTokenVerifier verifier;
    private final IdentityAuthController tokenResponses;

    public IdentityOidcLoginController(
            OidcAuthenticationService authentication,
            TrustedOidcIdTokenVerifier verifier,
            IdentityAuthController tokenResponses) {
        this.authentication = authentication;
        this.verifier = verifier;
        this.tokenResponses = tokenResponses;
    }

    @PostMapping("/login")
    public ResponseEntity<IdentityAuthController.LoginResponse> login(
            @RequestBody OidcLoginRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String providerCode = request.providerCode().trim().toUpperCase(Locale.ROOT);
        var verified = verifier.verify(
                new OidcIdTokenVerificationRequest(providerCode, request.idToken(), request.expectedNonce()));
        var result = authentication.login(new OidcLoginCommand(
                verified.providerCode(),
                verified.issuer(),
                verified.subject(),
                verified.email(),
                correlation(correlationId)));
        return tokenResponses.tokenResponse(result);
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record OidcLoginRequest(
            String providerCode, String idToken, String expectedNonce) {
        public OidcLoginRequest {
            Objects.requireNonNull(providerCode, "providerCode");
            Objects.requireNonNull(idToken, "idToken");
            Objects.requireNonNull(expectedNonce, "expectedNonce");
            if (providerCode.isBlank() || idToken.isBlank() || expectedNonce.isBlank()) {
                throw new IllegalArgumentException("OIDC login fields must be present");
            }
        }

        @Override
        public String toString() {
            return "OidcLoginRequest[providerCode=" + providerCode
                    + ", idToken=<redacted>, expectedNonce=<redacted>]";
        }
    }
}
