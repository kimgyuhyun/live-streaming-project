package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.livestream.TestcontainersConfiguration;
import com.livestream.common.error.RetryLaterException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

// 진짜 Redis 컨테이너 위에서 센다. 다른 @SpringBootTest와 같은 설정이라 컨텍스트·컨테이너를 같이 쓴다.
// Redis를 테스트끼리 같이 쓰므로 이메일·IP는 매번 새로 만든다.
// 만료 시간은 기다리지 않고, 키의 만료를 일부러 짧게 바꿔 둔 뒤 다음 실패가 어떻게 잡는지 본다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LoginAttemptLimiterTest {

    private static final long WINDOW_SECONDS = Duration.ofMinutes(15).toSeconds();

    @Autowired
    private LoginAttemptLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    private static String newEmail() {
        return "limit" + ThreadLocalRandom.current().nextInt(100_000_000) + "@example.com";
    }

    private static String newIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private static String emailKey(String email) {
        return "login:fail:email:" + email;
    }

    private static String ipKey(String ip) {
        return "login:fail:ip:" + ip;
    }

    // 비교 전에 세고 성공을 알리지 않으면 그대로 실패 한 번이다
    private void fail(String email, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.acquire(email, ip);
        }
    }

    private long ttlOf(String key) {
        return redis.getExpire(key, TimeUnit.SECONDS);
    }

    private void shortenTtl(String key, long seconds) {
        redis.expire(key, Duration.ofSeconds(seconds));
    }

    // 만료 시각을 1ms 뒤로 당기고 지나가길 기다린다. 15분을 기다리지 않고 "만료된 뒤"를 만든다
    private void expireNow(String key) throws InterruptedException {
        redis.expire(key, Duration.ofMillis(1));
        Thread.sleep(10);
    }

    private String countOf(String key) {
        return redis.opsForValue().get(key);
    }

    @Nested
    @DisplayName("LoginAttemptLimiter")
    class Acquire {

        @Test
        @DisplayName("should reset email counter expiry to 15 minutes on every failure")
        void resetsEmailExpiryOnEveryFailure() {
            String email = newEmail();
            String ip = newIp();
            fail(email, ip, 1);
            List<Long> ttlsAfterEachFailure = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                shortenTtl(emailKey(email), 10);
                limiter.acquire(email, ip);
                ttlsAfterEachFailure.add(ttlOf(emailKey(email)));
            }

            assertThat(ttlsAfterEachFailure).allMatch(ttl -> ttl > WINDOW_SECONDS - 10);
        }

        @Test
        @DisplayName("should keep ip counter expiry from the first failure")
        void keepsIpExpiryFromFirstFailure() {
            String ip = newIp();
            fail(newEmail(), ip, 1);
            shortenTtl(ipKey(ip), 100);

            limiter.acquire(newEmail(), ip);

            assertThat(ttlOf(ipKey(ip))).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("should count attempts from different ips separately")
        void countsIpsSeparately() {
            String blockedIp = newIp();
            for (int i = 0; i < LoginAttemptLimiter.IP_LIMIT; i++) {
                fail(newEmail(), blockedIp, 1);
            }

            assertThatCode(() -> limiter.acquire(newEmail(), newIp())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should allow the fifth attempt when email has four failures")
        void allowsFifthAttempt() {
            String email = newEmail();
            fail(email, newIp(), 4);

            assertThatCode(() -> limiter.acquire(email, newIp())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should block with remaining seconds when email has five failures")
        void blocksAfterFiveFailures() {
            String email = newEmail();
            fail(email, newIp(), 5);

            assertThatThrownBy(() -> limiter.acquire(email, newIp()))
                    .isInstanceOfSatisfying(RetryLaterException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, WINDOW_SECONDS));
        }

        // 앞의 네 번이 오래전이었던 것처럼 만료를 줄여 두면, 첫 실패 기준일 때만 남은 시간이 짧게 나온다
        @Test
        @DisplayName("should report remaining time from the fifth failure when blocked")
        void reportsRemainingTimeFromFifthFailure() {
            String email = newEmail();
            fail(email, newIp(), 4);
            shortenTtl(emailKey(email), 100);
            fail(email, newIp(), 1);

            assertThatThrownBy(() -> limiter.acquire(email, newIp()))
                    .isInstanceOfSatisfying(RetryLaterException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).isGreaterThan(WINDOW_SECONDS - 10));
        }

        @Test
        @DisplayName("should not extend the block when a blocked attempt arrives")
        void doesNotExtendBlock() {
            String email = newEmail();
            fail(email, newIp(), 5);
            shortenTtl(emailKey(email), 100);

            catchThrowable(() -> limiter.acquire(email, newIp()));

            assertThat(ttlOf(emailKey(email))).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("should block the 21st attempt from an ip with 20 failures across different emails")
        void blocksIpAfterTwentyFailures() {
            String ip = newIp();
            for (int i = 0; i < LoginAttemptLimiter.IP_LIMIT; i++) {
                fail(newEmail(), ip, 1);
            }

            assertThatThrownBy(() -> limiter.acquire(newEmail(), ip)).isInstanceOf(RetryLaterException.class);
        }

        @Test
        @DisplayName("should not count successful logins toward the ip limit")
        void doesNotCountSuccessesTowardIpLimit() {
            String ip = newIp();
            for (int i = 0; i < LoginAttemptLimiter.IP_LIMIT; i++) {
                String email = newEmail();
                limiter.acquire(email, ip);
                limiter.succeeded(email, ip);
            }

            assertThatCode(() -> limiter.acquire(newEmail(), ip)).doesNotThrowAnyException();
        }

        // Redis TTL은 남은 시간을 반올림해 0.5초 미만이면 0을 준다. 400ms 남은 차단도 막혀야 한다
        @Test
        @DisplayName("should block when the email block has less than a second left")
        void blocksInLastSecond() {
            String email = newEmail();
            fail(email, newIp(), 5);
            redis.expire(emailKey(email), Duration.ofMillis(400));

            assertThatThrownBy(() -> limiter.acquire(email, newIp()))
                    .isInstanceOfSatisfying(RetryLaterException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1L));
        }

        @Test
        @DisplayName("should allow the attempt and start a new count after the email block expires")
        void startsNewCountAfterBlockExpires() throws InterruptedException {
            String email = newEmail();
            fail(email, newIp(), 5);
            expireNow(emailKey(email));

            limiter.acquire(email, newIp());

            assertThat(countOf(emailKey(email))).isEqualTo("1");
        }

        @Test
        @DisplayName("should report about 15 minutes remaining when an ip is blocked right after its 20th failure")
        void reportsFifteenMinutesForIpBlock() {
            String ip = newIp();
            for (int i = 0; i < LoginAttemptLimiter.IP_LIMIT; i++) {
                fail(newEmail(), ip, 1);
            }

            assertThatThrownBy(() -> limiter.acquire(newEmail(), ip))
                    .isInstanceOfSatisfying(RetryLaterException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).isBetween(WINDOW_SECONDS - 9, WINDOW_SECONDS));
        }

        @Test
        @DisplayName("should not create an ip counter when a success arrives after the ip counter expired")
        void doesNotCreateIpCounterAfterExpiry() throws InterruptedException {
            String email = newEmail();
            String ip = newIp();
            limiter.acquire(email, ip);
            expireNow(ipKey(ip));

            limiter.succeeded(email, ip);

            assertThat(redis.hasKey(ipKey(ip))).isFalse();
        }

        @Test
        @DisplayName("should not count a blocked attempt toward the ip counter")
        void doesNotCountBlockedAttemptTowardIp() {
            String email = newEmail();
            String ip = newIp();
            fail(email, ip, 5);

            catchThrowable(() -> limiter.acquire(email, ip));

            assertThat(countOf(ipKey(ip))).isEqualTo("5");
        }
    }
}
