package com.idea2strategy.backend.application.batch;

public interface BatchRunEvidencePort {
    void record(DeadlineBatchOrchestrator.RunSummary summary);
}
