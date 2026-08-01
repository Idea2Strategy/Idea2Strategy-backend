package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.common.DomainEventPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RecordingDomainEventPublisher implements DomainEventPublisher {
    private final List<Object> events = new ArrayList<>();

    @Override
    public void publish(Object event) {
        events.add(event);
    }

    public List<Object> publishedEvents() {
        return Collections.unmodifiableList(events);
    }
}
