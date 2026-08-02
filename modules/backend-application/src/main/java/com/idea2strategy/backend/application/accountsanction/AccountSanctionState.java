package com.idea2strategy.backend.application.accountsanction;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AccountSanctionState(UUID accountId, long version, List<Sanction> sanctions) {

    public AccountSanctionState {
        Objects.requireNonNull(accountId, "accountId");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        sanctions = List.copyOf(sanctions);
        var ids = new HashSet<UUID>();
        if (!sanctions.stream().allMatch(sanction -> ids.add(sanction.id()))) {
            throw new IllegalArgumentException("sanction ids must be unique");
        }
    }

    public static AccountSanctionState empty(UUID accountId) {
        return new AccountSanctionState(accountId, 0, List.of());
    }

    public Sanction find(UUID sanctionId) {
        return sanctions.stream().filter(sanction -> sanction.id().equals(sanctionId)).findFirst().orElse(null);
    }

    public boolean hasOtherEffectiveSanction(UUID sanctionId, Instant evaluatedAt) {
        return sanctions.stream()
                .filter(sanction -> !sanction.id().equals(sanctionId))
                .anyMatch(sanction -> sanction.effectiveAt(evaluatedAt));
    }

    public record Sanction(
            UUID id,
            Type type,
            Status status,
            String reasonCode,
            Instant appliedAt,
            Instant effectiveAt,
            Instant expiresAt,
            UUID sourceCaseId) {

        public Sanction {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
            Objects.requireNonNull(appliedAt, "appliedAt");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            if (effectiveAt.isBefore(appliedAt)) {
                throw new IllegalArgumentException("effectiveAt cannot precede appliedAt");
            }
            if (type == Type.SUSPENSION && expiresAt == null) {
                throw new IllegalArgumentException("temporary sanctions require expiresAt");
            }
            if (type == Type.PERMANENT && expiresAt != null) {
                throw new IllegalArgumentException("permanent sanctions cannot expire");
            }
            if (expiresAt != null && !effectiveAt.isBefore(expiresAt)) {
                throw new IllegalArgumentException("expiresAt must follow effectiveAt");
            }
        }

        public boolean effectiveAt(Instant evaluatedAt) {
            return status == Status.ACTIVE
                    && !effectiveAt.isAfter(evaluatedAt)
                    && (expiresAt == null || evaluatedAt.isBefore(expiresAt));
        }
    }

    public enum Type {
        SUSPENSION,
        PERMANENT
    }

    public enum Status {
        ACTIVE,
        LIFTED,
        EXPIRED
    }
}
