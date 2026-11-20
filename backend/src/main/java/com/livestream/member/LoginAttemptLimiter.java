package com.livestream.member;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 로그인 실패 횟수를 Redis에 센다 (PRD member M3). 키: login:fail:email:{소문자 이메일}, login:fail:ip:{ip}
@Component
class LoginAttemptLimiter {

    static final int EMAIL_LIMIT = 5;
    static final int IP_LIMIT = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    LoginAttemptLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 비밀번호 비교 전에 부른다. 상한에 닿았으면 아무것도 세지 않고 RetryLaterException, 아니면 두 카운터를 1씩 올린다
    void acquire(String email, String ip) {
        throw new UnsupportedOperationException("not implemented");
    }

    // 로그인 성공 뒤: 이메일 카운터를 지우고, 미리 센 IP 카운터 1을 되돌린다
    void succeeded(String email, String ip) {
        throw new UnsupportedOperationException("not implemented");
    }
}
