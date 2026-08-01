package com.idea2strategy.backend.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts", schema = "identity")
public class IdentityAccountJpaEntity {
    @Id
    private UUID id;

    @Column(name = "lifecycle_status", nullable = false, columnDefinition = "identity.account_lifecycle_status")
    private String lifecycleStatus;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdentityAccountJpaEntity() {}
}
