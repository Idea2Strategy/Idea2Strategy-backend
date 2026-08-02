package com.idea2strategy.backend.api.identity;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("identity.oidc")
public class TrustedOidcProperties {
    private boolean enabled;
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    Map<String, TrustedOidcProviderConfiguration> trustedProviders() {
        var result = new LinkedHashMap<String, TrustedOidcProviderConfiguration>();
        providers.forEach((code, provider) -> result.put(code.toUpperCase(java.util.Locale.ROOT),
                new TrustedOidcProviderConfiguration(
                        code.toUpperCase(java.util.Locale.ROOT),
                        provider.getIssuer(),
                        provider.getJwksUri(),
                        provider.getAudiences())));
        return Map.copyOf(result);
    }

    public static class Provider {
        private String issuer;
        private URI jwksUri;
        private Set<String> audiences = Set.of();

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public URI getJwksUri() { return jwksUri; }
        public void setJwksUri(URI jwksUri) { this.jwksUri = jwksUri; }
        public Set<String> getAudiences() { return audiences; }
        public void setAudiences(Set<String> audiences) { this.audiences = audiences; }
    }
}
