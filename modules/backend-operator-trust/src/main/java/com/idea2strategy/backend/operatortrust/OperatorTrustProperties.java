package com.idea2strategy.backend.operatortrust;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("idea2strategy.operator-auth")
public class OperatorTrustProperties {
    private boolean enabled;
    private boolean secureCookie = true;
    private Duration idleLifetime = Duration.ofMinutes(15);
    private Duration absoluteLifetime = Duration.ofHours(8);
    private int passwordMemoryKb = 65536;
    private int passwordIterations = 3;
    private int passwordParallelism = 1;
    private String throttleRedisUri = "";
    private Duration throttleWindow = Duration.ofMinutes(5);
    private int throttleLoginLimit = 10;
    private int throttleSourceLimit = 60;
    private VersionedKey totpEncryption = new VersionedKey();
    private VersionedKey sessionHmac = new VersionedKey();
    private VersionedKey csrfHmac = new VersionedKey();
    private VersionedKey sourceHmac = new VersionedKey();
    private VersionedKey loginHmac = new VersionedKey();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSecureCookie() { return secureCookie; }
    public void setSecureCookie(boolean secureCookie) { this.secureCookie = secureCookie; }
    public Duration getIdleLifetime() { return idleLifetime; }
    public void setIdleLifetime(Duration value) { this.idleLifetime = value; }
    public Duration getAbsoluteLifetime() { return absoluteLifetime; }
    public void setAbsoluteLifetime(Duration value) { this.absoluteLifetime = value; }
    public int getPasswordMemoryKb() { return passwordMemoryKb; }
    public void setPasswordMemoryKb(int value) { this.passwordMemoryKb = value; }
    public int getPasswordIterations() { return passwordIterations; }
    public void setPasswordIterations(int value) { this.passwordIterations = value; }
    public int getPasswordParallelism() { return passwordParallelism; }
    public void setPasswordParallelism(int value) { this.passwordParallelism = value; }
    public String getThrottleRedisUri() { return throttleRedisUri; }
    public void setThrottleRedisUri(String value) { this.throttleRedisUri = value; }
    public Duration getThrottleWindow() { return throttleWindow; }
    public void setThrottleWindow(Duration value) { this.throttleWindow = value; }
    public int getThrottleLoginLimit() { return throttleLoginLimit; }
    public void setThrottleLoginLimit(int value) { this.throttleLoginLimit = value; }
    public int getThrottleSourceLimit() { return throttleSourceLimit; }
    public void setThrottleSourceLimit(int value) { this.throttleSourceLimit = value; }
    public VersionedKey getTotpEncryption() { return totpEncryption; }
    public void setTotpEncryption(VersionedKey value) { this.totpEncryption = value; }
    public VersionedKey getSessionHmac() { return sessionHmac; }
    public void setSessionHmac(VersionedKey value) { this.sessionHmac = value; }
    public VersionedKey getCsrfHmac() { return csrfHmac; }
    public void setCsrfHmac(VersionedKey value) { this.csrfHmac = value; }
    public VersionedKey getSourceHmac() { return sourceHmac; }
    public void setSourceHmac(VersionedKey value) { this.sourceHmac = value; }
    public VersionedKey getLoginHmac() { return loginHmac; }
    public void setLoginHmac(VersionedKey value) { this.loginHmac = value; }

    public void validate() {
        if (idleLifetime == null || idleLifetime.isNegative() || idleLifetime.isZero()
                || absoluteLifetime == null || absoluteLifetime.compareTo(idleLifetime) < 0
                || passwordMemoryKb < 8192 || passwordIterations < 1 || passwordParallelism < 1
                || throttleWindow == null || throttleWindow.isNegative() || throttleWindow.isZero()
                || throttleLoginLimit < 1 || throttleSourceLimit < throttleLoginLimit) {
            throw new IllegalArgumentException("OPERATOR_AUTH_CONFIGURATION_INVALID");
        }
        totpEncryption.require("TOTP");
        sessionHmac.require("SESSION");
        csrfHmac.require("CSRF");
        sourceHmac.require("SOURCE");
        loginHmac.require("LOGIN");
    }

    public static class VersionedKey {
        private int version;
        private String key;
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        void require(String name) {
            if (version <= 0 || key == null || key.isBlank()) {
                throw new IllegalArgumentException("OPERATOR_" + name + "_KEY_CONFIGURATION_INVALID");
            }
        }
    }
}
