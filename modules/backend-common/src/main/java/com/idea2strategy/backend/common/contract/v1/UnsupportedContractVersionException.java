package com.idea2strategy.backend.common.contract.v1;

public final class UnsupportedContractVersionException extends IllegalArgumentException {
    private final String expected;
    private final String actual;

    public UnsupportedContractVersionException(String expected, String actual) {
        super("Unsupported contract version: expected " + expected + ", actual " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public String expected() {
        return expected;
    }

    public String actual() {
        return actual;
    }
}
