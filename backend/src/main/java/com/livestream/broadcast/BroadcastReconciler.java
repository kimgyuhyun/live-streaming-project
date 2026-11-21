package com.livestream.broadcast;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class BroadcastReconciler {

    private final BroadcastService broadcastService;

    BroadcastReconciler(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    void run() {
        throw new UnsupportedOperationException("not implemented");
    }
}
