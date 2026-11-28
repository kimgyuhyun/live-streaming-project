package com.livestream.broadcast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// 동시 시청자 수 한 회차 집계(#39, PRD viewer V1). MediaMTX 시청 세션을 live/{채널ID}별로 세어
// 진행 중 방송마다(0명 포함) 판에 쓴다. 진행 중 방송이 없는 경로는 버린다
@Component
class ViewerCounter {

    private static final Logger log = LoggerFactory.getLogger(ViewerCounter.class);

    private final MediaMtxClient mediaMtxClient;
    private final BroadcastMapper broadcastMapper;
    private final ViewerSnapshot viewerSnapshot;
    private final int maxSessionsPerIp;

    ViewerCounter(MediaMtxClient mediaMtxClient, BroadcastMapper broadcastMapper, ViewerSnapshot viewerSnapshot,
                  @Value("${app.viewer.max-sessions-per-ip}") int maxSessionsPerIp) {
        this.mediaMtxClient = mediaMtxClient;
        this.broadcastMapper = broadcastMapper;
        this.viewerSnapshot = viewerSnapshot;
        this.maxSessionsPerIp = maxSessionsPerIp;
    }

    // 잠금을 못 잡으면(다른 서버가 이번 회차를 센다) 아무것도 하지 않는다.
    // MediaMTX 호출이 실패하면 판을 쓰지 않는다 — 이전 판이 그대로 보인다. 실패만 남기고 밖으로 던지지 않는다.
    // 세션 주소(remoteAddr)는 남기지 않는다. 예외 메시지도 남기지 않는다(MediaMTX 응답 조각이 섞일 수 있다)
    void aggregate() {
        if (!viewerSnapshot.tryLock()) {
            return;
        }
        long startedAt = System.nanoTime();
        List<MediaMtxClient.HlsSession> sessions;
        try {
            sessions = mediaMtxClient.findHlsSessions();
        } catch (RestClientException e) {
            log.warn("시청자 수 집계 실패 cause={} elapsedMs={}", e.getClass().getSimpleName(), elapsedMs(startedAt));
            return;
        }
        Map<Long, Long> sessionsByChannel = countByChannel(sessions);
        List<LiveBroadcast> live = broadcastMapper.findLiveBroadcasts();
        Map<Long, Long> countsByBroadcast = new HashMap<>();
        for (LiveBroadcast broadcast : live) {
            countsByBroadcast.put(broadcast.id(), sessionsByChannel.getOrDefault(broadcast.channelId(), 0L));
        }
        viewerSnapshot.write(countsByBroadcast);
        log.info("시청자 수 집계 sessions={} broadcasts={} elapsedMs={}", sessions.size(), live.size(),
                elapsedMs(startedAt));
    }

    // 채널ID → 센 세션 수. 같은 채널에서 같은 IP의 세션은 maxSessionsPerIp개까지만 센다(#40)
    private Map<Long, Long> countByChannel(List<MediaMtxClient.HlsSession> sessions) {
        throw new UnsupportedOperationException("not implemented");
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
