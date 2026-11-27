package com.livestream.broadcast;

import com.livestream.channel.ViewerCountReader;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 시청자 수 집계의 Redis 키 묶음(#39, PRD viewer V1·V2). 키 이름을 이 클래스 한 곳에 둔다.
//   viewers:lock         이번 회차 잠금(SET NX, 4초 만료). 서버 2대 중 잡은 쪽만 센다
//   viewers:seq          판 번호(INCR). 서버 시계가 어긋나도 번호가 겹치지 않는다
//   viewers:{판}:count   방송ID → 시청자 수. 2분 뒤 만료
//   viewers:current      지금 판 번호. 만료를 걸지 않는다 — 판이 만료되면 가리키는 판이 없어 0이 된다
@Component
class ViewerSnapshot implements ViewerCountReader {

    static final String LOCK_KEY = "viewers:lock";
    static final String CURRENT_KEY = "viewers:current";
    static final Duration LOCK_TTL = Duration.ofSeconds(4);
    static final Duration SNAPSHOT_TTL = Duration.ofMinutes(2);

    private static final String SEQ_KEY = "viewers:seq";

    private final StringRedisTemplate redis;

    ViewerSnapshot(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 이번 회차 잠금. 잡았으면 true. 풀지 않는다 — 다 센 뒤 풀면 다른 서버가 같은 5초 안에 또 센다
    boolean tryLock() {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL));
    }

    // 새 판에 방송ID → 수를 쓰고, 다 쓴 다음 viewers:current를 새 판 번호로 바꾼다.
    // 진행 중 방송이 없으면 Redis는 빈 해시를 만들지 않는다. 그래도 current는 새 판으로 바꿔 이전 판이 남아 보이지 않게 한다
    void write(Map<Long, Long> countsByBroadcastId) {
        long snapshot = redis.opsForValue().increment(SEQ_KEY);
        String countKey = countKey(String.valueOf(snapshot));
        if (!countsByBroadcastId.isEmpty()) {
            Map<String, String> fields = new HashMap<>();
            countsByBroadcastId.forEach((broadcastId, count) -> fields.put(broadcastId.toString(), count.toString()));
            redis.opsForHash().putAll(countKey, fields);
            redis.expire(countKey, SNAPSHOT_TTL);
        }
        redis.opsForValue().set(CURRENT_KEY, String.valueOf(snapshot));
    }

    @Override
    public long viewerCount(long broadcastId) {
        String snapshot = redis.opsForValue().get(CURRENT_KEY);
        if (snapshot == null) {
            return 0;
        }
        Object count = redis.opsForHash().get(countKey(snapshot), String.valueOf(broadcastId));
        return count == null ? 0 : Long.parseLong((String) count);
    }

    private static String countKey(String snapshot) {
        return "viewers:" + snapshot + ":count";
    }
}
