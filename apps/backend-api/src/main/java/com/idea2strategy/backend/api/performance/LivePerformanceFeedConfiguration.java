package com.idea2strategy.backend.api.performance;

import com.idea2strategy.backend.persistence.performance.CanonicalLivePerformanceFeed;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the live room projections moving while evaluations run.
 *
 * <p>The feed itself is idempotent — an unchanged segment applies nothing — so the schedule is a
 * refresh cadence, not a delivery guarantee. {@code performance.live-feed.enabled=false} turns it
 * off for environments that drive the feed explicitly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBean(CanonicalLivePerformanceFeed.class)
@ConditionalOnProperty(
        prefix = "performance.live-feed", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LivePerformanceFeedConfiguration {

    @Bean
    LivePerformanceFeedWorker livePerformanceFeedWorker(CanonicalLivePerformanceFeed feed) {
        return new LivePerformanceFeedWorker(feed);
    }

    public static final class LivePerformanceFeedWorker {
        private final CanonicalLivePerformanceFeed feed;

        LivePerformanceFeedWorker(CanonicalLivePerformanceFeed feed) {
            this.feed = feed;
        }

        @Scheduled(fixedDelayString = "${performance.live-feed.delay:PT10S}")
        public void refresh() {
            feed.refreshActiveSegments();
        }
    }
}
