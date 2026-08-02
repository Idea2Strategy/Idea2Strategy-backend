package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.List;

public interface AccountLifecycleCandidateQueryPort {
    /** Finds active accounts last authenticated no later than the conservative candidate cutoff. */
    List<AccountLifecycleSnapshot> findActiveDormancyCandidates(Instant candidateCutoff, int limit);
}
