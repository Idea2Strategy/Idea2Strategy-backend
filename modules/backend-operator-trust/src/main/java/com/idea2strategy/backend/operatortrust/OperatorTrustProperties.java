package com.idea2strategy.backend.operatortrust;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("idea2strategy.operator-auth")
public class OperatorTrustProperties {
    private boolean enabled;
    private String issuer;
    private URI jwkSetUri;
    private String audience;
    private String algorithm = "RS256";
    private Duration maximumTokenAge = Duration.ofMinutes(5);
    private Duration maximumMfaAge = Duration.ofMinutes(10);
    private Duration clockSkew = Duration.ofSeconds(30);
    private Set<String> allowedAcrValues = Set.of();
    private Set<String> allowedAmrValues = Set.of("mfa");
    private String mfaClaimName;
    private Set<String> allowedMfaClaimValues = Set.of();
    private int currentSubjectHmacKeyVersion;
    private String currentSubjectHmacKey;
    private Integer previousSubjectHmacKeyVersion;
    private String previousSubjectHmacKey;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public OperatorTrustConfiguration validated() {
        if (!"RS256".equals(algorithm)) {
            throw new IllegalArgumentException("OPERATOR_TRUST_CONFIGURATION_INVALID");
        }
        Map<Integer, byte[]> keys = new LinkedHashMap<>();
        keys.put(currentSubjectHmacKeyVersion, OperatorTrustConfiguration.decodeKey(currentSubjectHmacKey));
        if (previousSubjectHmacKeyVersion != null || previousSubjectHmacKey != null) {
            if (previousSubjectHmacKeyVersion == null || previousSubjectHmacKey == null
                    || previousSubjectHmacKeyVersion == currentSubjectHmacKeyVersion) {
                throw new IllegalArgumentException("OPERATOR_TRUST_CONFIGURATION_INVALID");
            }
            keys.put(previousSubjectHmacKeyVersion,
                    OperatorTrustConfiguration.decodeKey(previousSubjectHmacKey));
        }
        return new OperatorTrustConfiguration(
                issuer, jwkSetUri, audience, maximumTokenAge, maximumMfaAge, clockSkew,
                allowedAcrValues, allowedAmrValues, mfaClaimName, allowedMfaClaimValues,
                keys, currentSubjectHmacKeyVersion);
    }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public URI getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(URI jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public Duration getMaximumTokenAge() { return maximumTokenAge; }
    public void setMaximumTokenAge(Duration maximumTokenAge) { this.maximumTokenAge = maximumTokenAge; }
    public Duration getMaximumMfaAge() { return maximumMfaAge; }
    public void setMaximumMfaAge(Duration maximumMfaAge) { this.maximumMfaAge = maximumMfaAge; }
    public Duration getClockSkew() { return clockSkew; }
    public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
    public Set<String> getAllowedAcrValues() { return allowedAcrValues; }
    public void setAllowedAcrValues(Set<String> values) { this.allowedAcrValues = values; }
    public Set<String> getAllowedAmrValues() { return allowedAmrValues; }
    public void setAllowedAmrValues(Set<String> values) { this.allowedAmrValues = values; }
    public String getMfaClaimName() { return mfaClaimName; }
    public void setMfaClaimName(String value) { this.mfaClaimName = value; }
    public Set<String> getAllowedMfaClaimValues() { return allowedMfaClaimValues; }
    public void setAllowedMfaClaimValues(Set<String> values) { this.allowedMfaClaimValues = values; }
    public int getCurrentSubjectHmacKeyVersion() { return currentSubjectHmacKeyVersion; }
    public void setCurrentSubjectHmacKeyVersion(int value) { this.currentSubjectHmacKeyVersion = value; }
    public String getCurrentSubjectHmacKey() { return currentSubjectHmacKey; }
    public void setCurrentSubjectHmacKey(String value) { this.currentSubjectHmacKey = value; }
    public Integer getPreviousSubjectHmacKeyVersion() { return previousSubjectHmacKeyVersion; }
    public void setPreviousSubjectHmacKeyVersion(Integer value) { this.previousSubjectHmacKeyVersion = value; }
    public String getPreviousSubjectHmacKey() { return previousSubjectHmacKey; }
    public void setPreviousSubjectHmacKey(String value) { this.previousSubjectHmacKey = value; }
}
