package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.common.IdGenerator;
import java.util.Objects;
import java.util.UUID;

public final class FixedIdGenerator implements IdGenerator {
    private final UUID id;

    public FixedIdGenerator(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public UUID nextId() {
        return id;
    }
}
