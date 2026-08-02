package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OperatorCaseQueuePort {
    Page findQueue(Query query, Instant evaluatedAt);

    Optional<OperatorCaseState> findCase(UUID caseId, Instant evaluatedAt);

    record Query(
            Set<UserCaseType> caseTypes,
            Set<UserCaseStatus> statuses,
            UUID assigneeOperatorId,
            String cursor,
            int limit) {
        public Query {
            caseTypes = Set.copyOf(caseTypes);
            statuses = Set.copyOf(statuses);
        }
    }

    record Item(
            UUID caseId,
            UserCaseType type,
            UserCaseStatus status,
            long version,
            UUID assigneeOperatorId,
            Instant updatedAt) {}

    record Page(List<Item> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
