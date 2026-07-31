package com.idea2strategy.backend.common.contract.v1;

public final class CommonContractVersions {
    public static final String AUTH_PRINCIPAL_V1 = "auth-principal.v1";
    public static final String EVENT_ENVELOPE_V1 = "event-envelope.v1";
    public static final String API_ERROR_V1 = "api-error.v1";
    public static final String PAGE_V1 = "page.v1";

    private CommonContractVersions() {}

    static String require(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new UnsupportedContractVersionException(expected, actual);
        }
        return actual;
    }
}
