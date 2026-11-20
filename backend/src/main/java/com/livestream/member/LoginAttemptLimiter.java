package com.livestream.member;

import com.livestream.common.error.ErrorCode;
import com.livestream.common.error.RetryLaterException;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

// 로그인 실패 횟수를 Redis에 센다 (PRD member M3). 키: login:fail:email:{소문자 이메일}, login:fail:ip:{ip}
// 서버 2대가 같은 횟수를 보도록, 확인과 증가를 Lua 스크립트 하나로 Redis 안에서 한 번에 한다
@Component
class LoginAttemptLimiter {

    static final int EMAIL_LIMIT = 5;
    static final int IP_LIMIT = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);

    // 막혔으면 남은 초(둘 다면 더 긴 쪽)를 돌려주고 아무것도 세지 않는다.
    // 막히지 않았으면 0을 돌려주고 센다. 이메일은 마지막 실패 기준(매번 만료를 다시), IP는 첫 실패 기준(처음에만 만료)
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local emailCount = tonumber(redis.call('GET', KEYS[1]) or '0')
            local ipCount = tonumber(redis.call('GET', KEYS[2]) or '0')
            local wait = 0
            if emailCount >= tonumber(ARGV[1]) then wait = math.max(wait, redis.call('TTL', KEYS[1])) end
            if ipCount >= tonumber(ARGV[2]) then wait = math.max(wait, redis.call('TTL', KEYS[2])) end
            if wait > 0 then return wait end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            if redis.call('INCR', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
            return 0
            """, Long.class);

    // 이메일 카운터를 지우고, 비교 전에 미리 센 IP 카운터 1을 되돌린다. 이미 만료됐으면 IP는 건드리지 않는다
    private static final RedisScript<Long> SUCCEEDED = RedisScript.of("""
            redis.call('DEL', KEYS[1])
            if tonumber(redis.call('GET', KEYS[2]) or '0') > 0 then redis.call('DECR', KEYS[2]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    LoginAttemptLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 비밀번호 비교 전에 부른다. 상한에 닿았으면 아무것도 세지 않고 RetryLaterException, 아니면 두 카운터를 1씩 올린다
    void acquire(String email, String ip) {
        Long wait = redis.execute(ACQUIRE, keys(email, ip),
                String.valueOf(EMAIL_LIMIT), String.valueOf(IP_LIMIT), String.valueOf(WINDOW.toSeconds()));
        if (wait != null && wait > 0) {
            throw new RetryLaterException(ErrorCode.MEMBER_LOGIN_BLOCKED, wait);
        }
    }

    // 로그인 성공 뒤: 이메일 카운터를 지우고, 미리 센 IP 카운터 1을 되돌린다
    void succeeded(String email, String ip) {
        redis.execute(SUCCEEDED, keys(email, ip));
    }

    private static List<String> keys(String email, String ip) {
        return List.of("login:fail:email:" + email, "login:fail:ip:" + ip);
    }
}
