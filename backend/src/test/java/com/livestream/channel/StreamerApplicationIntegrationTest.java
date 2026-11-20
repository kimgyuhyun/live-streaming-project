package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// 진짜 Oracle·Redis 컨테이너와 보안 필터·Spring Session 필터를 모두 거친다.
// DB를 테스트끼리 같이 쓰므로 회원은 매번 새로 만든다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StreamerApplicationIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8080";
    private static final String PASSWORD = "abcd1234";
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private StreamerApplicationService streamerApplicationService;

    @Autowired
    private StreamerApplicationMapper streamerApplicationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newEmail() {
        return "s" + unique() + "@example.com";
    }

    // 새 회원을 만들고 id를 돌려준다
    private long signUp(String email) {
        return memberService.signUp(new SignUpRequest(email, PASSWORD, "신청" + unique())).id();
    }

    private Cookie loggedIn(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SESSION_COOKIE);
    }

    private int pendingCountOf(long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamer_application WHERE member_id = ? AND status = 'PENDING'",
                Integer.class, memberId);
    }

    // 대기 신청 확인만 늘 "없음"으로 답하고 나머지는 진짜 매퍼에 맡긴다.
    // 동시 신청에서 확인을 함께 통과한 상황을 매번 만들어, 진짜 Oracle의 제약 위반 경로를 탄다
    private StreamerApplicationService serviceSkippingPendingCheck() {
        StreamerApplicationMapper skipping = new StreamerApplicationMapper() {
            @Override
            public void insert(StreamerApplication application) {
                streamerApplicationMapper.insert(application);
            }

            @Override
            public boolean existsPendingByMemberId(long memberId) {
                return false;
            }

            @Override
            public Optional<ApplicationStatus> findLatestStatusByMemberId(long memberId) {
                return streamerApplicationMapper.findLatestStatusByMemberId(memberId);
            }
        };
        return new StreamerApplicationService(skipping);
    }

    @Nested
    @DisplayName("StreamerApplicationIntegration")
    class Integration {

        @Test
        @DisplayName("should return PENDING from my application after member applies with session cookie")
        void returnsPendingAfterApplying() throws Exception {
            String email = newEmail();
            signUp(email);
            Cookie session = loggedIn(email);

            mockMvc.perform(post("/api/streamer-applications").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session));

            mockMvc.perform(get("/api/streamer-applications/me").cookie(session))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("should keep one pending application when two applications from the same member arrive at once")
        void keepsOnePendingForConcurrentApplications() throws Exception {
            long memberId = signUp(newEmail());
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<String> task = () -> {
                    start.await();
                    try {
                        streamerApplicationService.apply(memberId, Role.MEMBER);
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getCode().name();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            for (Future<String> result : results) {
                result.get();
            }
            pool.shutdown();

            assertThat(pendingCountOf(memberId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_PENDING when pending check passes but a pending application is already saved")
        void mapsPendingConstraintViolation() {
            long memberId = signUp(newEmail());
            streamerApplicationMapper.insert(new StreamerApplication(memberId, ApplicationStatus.PENDING));

            assertThatThrownBy(() -> serviceSkippingPendingCheck().apply(memberId, Role.MEMBER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_PENDING);
        }

        @Test
        @DisplayName("should save a new pending application and report PENDING when member applies again after rejection")
        void appliesAgainAfterRejection() {
            long memberId = signUp(newEmail());
            // 거절 API는 #7에서 생긴다. 거절된 신청을 직접 넣는다
            streamerApplicationMapper.insert(new StreamerApplication(memberId, ApplicationStatus.REJECTED));

            streamerApplicationService.apply(memberId, Role.MEMBER);

            assertThat(streamerApplicationService.findMine(memberId).status()).isEqualTo(ApplicationStatus.PENDING);
        }

        @Test
        @DisplayName("should return 401 with code UNAUTHENTICATED when application is requested without login")
        void rejectsWithoutLogin() throws Exception {
            mockMvc.perform(post("/api/streamer-applications").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("should return 403 when application request comes from another origin")
        void rejectsAnotherOrigin() throws Exception {
            String email = newEmail();
            signUp(email);
            Cookie session = loggedIn(email);

            mockMvc.perform(post("/api/streamer-applications")
                            .header(HttpHeaders.ORIGIN, "http://evil.example")
                            .cookie(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN_ORIGIN"));
        }
    }
}
