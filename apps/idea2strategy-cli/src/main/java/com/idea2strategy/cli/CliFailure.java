package com.idea2strategy.cli;

final class CliFailure extends RuntimeException {
    private final int exitCode;
    private final String errorCode;
    private final Integer status;

    CliFailure(int exitCode, String errorCode, String message) {
        this(exitCode, errorCode, message, null);
    }

    CliFailure(int exitCode, String errorCode, String message, Integer status) {
        super(message);
        this.exitCode = exitCode;
        this.errorCode = errorCode;
        this.status = status;
    }

    int exitCode() {
        return exitCode;
    }

    String errorCode() {
        return errorCode;
    }

    Integer status() {
        return status;
    }
}
