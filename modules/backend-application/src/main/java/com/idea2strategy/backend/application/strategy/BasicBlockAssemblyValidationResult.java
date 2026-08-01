package com.idea2strategy.backend.application.strategy;

import java.util.List;

public record BasicBlockAssemblyValidationResult(List<BasicBlockAssemblyIssue> issues) {
    public BasicBlockAssemblyValidationResult {
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.isEmpty();
    }
}
