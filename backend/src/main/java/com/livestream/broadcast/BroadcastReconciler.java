package com.livestream.broadcast;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

// 1분마다 방송 상태를 MediaMTX와 맞춘다(PRD channel C2). 서버 2대가 각자 돌려도 DB 제약이 방송을 하나로 지킨다.
// app.scheduling.enabled=false면 띄우지 않는다(테스트 설정 — 대조 테스트는 reconcile()을 직접 부른다)
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class BroadcastReconciler {

    private static final Logger log = LoggerFactory.getLogger(BroadcastReconciler.class);

    private final BroadcastService broadcastService;

    BroadcastReconciler(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    // 실패는 남기고 다음 회차를 기다린다. 예외 메시지는 남기지 않는다(MediaMTX 응답 조각이 섞일 수 있다)
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    void run() {
        try {
            broadcastService.reconcile();
        } catch (RuntimeException e) {
            log.warn("방송 상태 대조 실패 cause={}", e.getClass().getSimpleName());
        }
    }
}
