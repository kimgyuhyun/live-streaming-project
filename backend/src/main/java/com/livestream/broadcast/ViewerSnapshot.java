package com.livestream.broadcast;

import com.livestream.channel.ViewerCountReader;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 시청자 수 집계의 Redis 키 묶음(#39, PRD viewer V1·V2). 키 이름을 이 클래스 한 곳에 둔다
@Component
class ViewerSnapshot implements ViewerCountReader {

    static final String LOCK_KEY = "viewers:lock";
    static final String CURRENT_KEY = "viewers:current";
    static final Duration LOCK_TTL = Duration.ofSeconds(4);
    static final Duration SNAPSHOT_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;

    ViewerSnapshot(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 이번 회차 잠금. 잡았으면 true
    boolean tryLock() {
        throw new UnsupportedOperationException("not implemented");
    }

    // 새 판에 방송ID → 수를 쓰고, 다 쓴 다음 viewers:current를 새 판 번호로 바꾼다
    void write(Map<Long, Long> countsByBroadcastId) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public long viewerCount(long broadcastId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
