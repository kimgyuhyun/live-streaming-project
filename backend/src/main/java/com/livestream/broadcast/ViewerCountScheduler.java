package com.livestream.broadcast;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

// 5초마다 동시 시청자 수를 센다(#39, PRD viewer V1). 서버 2대가 다 돌려도 잠금을 잡은 한 대만 센다(ViewerCounter).
// app.scheduling.enabled=false면 띄우지 않는다(테스트 설정 — 집계 테스트는 aggregate()를 직접 부른다)
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class ViewerCountScheduler {

    private final ViewerCounter viewerCounter;

    ViewerCountScheduler(ViewerCounter viewerCounter) {
        this.viewerCounter = viewerCounter;
    }

    // 시작 간격 5초(fixedRate). 집계가 오래 걸려도 주기가 늘어나지 않는다
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    void run() {
        viewerCounter.aggregate();
    }
}
