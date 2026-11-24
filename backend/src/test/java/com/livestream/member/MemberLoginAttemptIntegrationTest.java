package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 진짜 Oracle·Redis와 보안 필터를 거쳐 로그인 시도 제한을 본다.
// DB·Redis를 테스트끼리 같이 쓰므로 이메일·닉네임·IP는 매번 새로 만든다.
// IP를 정하지 않으면 모든 요청이 127.0.0.1이 되어 다른 테스트의 실패가 IP 카운터에 쌓인다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberLoginAttemptIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8000";
    private static final String PASSWORD = "abcd1234";
    private static final String WRONG_PASSWORD = "wrong1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private String signUp() {
        String email = "a" + unique() + "@example.com";
        memberService.signUp(new SignUpRequest(email, PASSWORD, "시도" + unique()));
        return email;
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
    }

    private int statusOf(String email, String password, String ip) throws Exception {
        return login(email, password, ip).andReturn().getResponse().getStatus();
    }

    @Nested
    @DisplayName("MemberLoginAttemptIntegration")
    class LoginAttemptIntegration {

        @Test
        @DisplayName("should log in after four failures and block only after five more failures")
        void resetsEmailCounterOnSuccess() throws Exception {
            String email = signUp();
            String ip = newIp();
            List<Integer> statuses = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                statuses.add(statusOf(email, WRONG_PASSWORD, ip));
            }
            statuses.add(statusOf(email, PASSWORD, ip));
            for (int i = 0; i < 5; i++) {
                statuses.add(statusOf(email, WRONG_PASSWORD, ip));
            }
            statuses.add(statusOf(email, PASSWORD, ip));

            assertThat(statuses).containsExactly(401, 401, 401, 401, 200, 401, 401, 401, 401, 401, 429);
        }

        // 비교 횟수를 세려고 진짜 매퍼·리미터 위에 비교를 세는 인코더만 끼운 서비스를 만든다
        @Test
        @DisplayName("should compare password at most five times when 20 wrong-password logins for one email arrive at once")
        void comparesAtMostFiveTimesUnderConcurrency() throws Exception {
            String email = signUp();
            String ip = newIp();
            AtomicInteger comparisons = new AtomicInteger();
            BCryptPasswordEncoder countingEncoder = new BCryptPasswordEncoder() {
                @Override
                public boolean matches(CharSequence rawPassword, String encodedPassword) {
                    comparisons.incrementAndGet();
                    return super.matches(rawPassword, encodedPassword);
                }
            };
            MemberService service = new MemberService(memberMapper, countingEncoder, loginAttemptLimiter, sessionRepository);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(20);
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.login(new LoginRequest(email, WRONG_PASSWORD), ip);
                    } catch (RuntimeException ignored) {
                        // 401·429 모두 여기로 온다. 세는 것은 비교 횟수뿐이다
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertThat(comparisons.get()).isEqualTo(5);
        }

        @Test
        @DisplayName("should return 429 with Retry-After when correct password is sent after five failures")
        void blocksCorrectPasswordAfterFiveFailures() throws Exception {
            String email = signUp();
            String ip = newIp();
            for (int i = 0; i < 5; i++) {
                login(email, WRONG_PASSWORD, ip);
            }

            login(email, PASSWORD, ip)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        }

        @Test
        @DisplayName("should return code MEMBER_LOGIN_BLOCKED with minutes message when blocked")
        void returnsBlockedCodeAndMessage() throws Exception {
            String email = signUp();
            String ip = newIp();
            for (int i = 0; i < 5; i++) {
                login(email, WRONG_PASSWORD, ip);
            }

            login(email, PASSWORD, ip)
                    .andExpect(jsonPath("$.code").value("MEMBER_LOGIN_BLOCKED"))
                    .andExpect(jsonPath("$.message").value("로그인 시도가 너무 많습니다. 15분 후 다시 시도해 주세요"));
        }
    }
}
