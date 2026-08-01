package com.idea2strategy.backend.application.common;

public interface DomainEventPublisher {
    void publish(Object event);
}
