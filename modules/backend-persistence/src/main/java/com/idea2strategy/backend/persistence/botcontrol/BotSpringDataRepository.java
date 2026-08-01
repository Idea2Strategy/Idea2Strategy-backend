package com.idea2strategy.backend.persistence.botcontrol;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotSpringDataRepository extends JpaRepository<BotJpaEntity, UUID> {}
