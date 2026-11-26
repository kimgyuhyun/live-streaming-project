package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// 진짜 Oracle·Redis 컨테이너와 보안 필터·Spring Session 필터를 모두 거친다.
// 스트리머는 실제 흐름(가입 → 신청 → 운영자 승인)으로 만든다. DB를 테스트끼리 같이 쓰므로 매번 새로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudioTermsIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8000";
    private static final String PASSWORD = "abcd1234";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String TERMS_PATH = "/api/studio/terms";
    private static final String AGREE_PATH = "/api/studio/terms-agreement";
    private static final String BOTH = "{\"terms\":[\"STUDIO_TERMS\",\"CONTENT_GUIDELINE\"]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private StreamerApplicationService applicationService;

    @Autowired
    private StreamerApplicationReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorChannelInitializer operatorChannelInitializer;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newEmail() {
        return "t" + unique() + "@example.com";
    }

    // 로그인 시도는 IP별로도 센다. 테스트끼리 같은 Redis를 쓰므로 요청마다 다른 IP로 보낸다
    private static RequestPostProcessor randomIp() {
        String ip = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256)
                + "." + ThreadLocalRandom.current().nextInt(1, 255);
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long signUp(String email) {
        return memberService.signUp(new SignUpRequest(email, PASSWORD, "약관" + unique())).id();
    }

    // 가입 → 신청 → 승인. 회원 id를 돌려준다(약관에는 동의하지 않은 스트리머)
    private long streamer(String email) {
        long memberId = signUp(email);
        applicationService.apply(memberId, Role.MEMBER);
        long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM streamer_application WHERE member_id = ? AND status = 'PENDING'", Long.class, memberId);
        reviewService.approve(applicationId);
        return memberId;
    }

    private Cookie loggedIn(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(randomIp())
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SESSION_COOKIE);
    }

    // 승인된 스트리머로 로그인한 세션
    private Cookie streamerSession() throws Exception {
        String email = newEmail();
        streamer(email);
        return loggedIn(email);
    }

    private ResultActions agree(Cookie session, String body) throws Exception {
        return mockMvc.perform(post(AGREE_PATH)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions termsStatus(Cookie session) throws Exception {
        return mockMvc.perform(get(TERMS_PATH).cookie(session));
    }

    private int rowsOf(long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_terms_agreement WHERE member_id = ?", Integer.class, memberId);
    }

    private long memberIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM member WHERE email = ?", Long.class, email);
    }

    @Nested
    @DisplayName("StudioTermsIntegration")
    class Integration {

        @Test
        @DisplayName("should return agreed false when a streamer who has not agreed asks for the terms status")
        void agreedFalseBeforeAgreeing() throws Exception {
            Cookie session = streamerSession();

            termsStatus(session).andExpect(jsonPath("$.agreed").value(false));
        }

        @Test
        @DisplayName("should return 204 and then agreed true when a streamer agrees to both required terms")
        void agreesToBoth() throws Exception {
            Cookie session = streamerSession();
            agree(session, BOTH).andExpect(status().isNoContent());

            termsStatus(session).andExpect(jsonPath("$.agreed").value(true));
        }

        @Test
        @DisplayName("should let the streamer read the studio channel after agreeing to the terms")
        void readsStudioChannelAfterAgreeing() throws Exception {
            Cookie session = streamerSession();
            agree(session, BOTH).andExpect(status().isNoContent());

            mockMvc.perform(get("/api/studio/channel").cookie(session)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("should let the streamer issue a stream key after agreeing to the terms")
        void issuesKeyAfterAgreeing() throws Exception {
            Cookie session = streamerSession();
            agree(session, BOTH).andExpect(status().isNoContent());

            mockMvc.perform(post("/api/studio/stream-key").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 204 and keep one row per terms when the streamer agrees twice")
        void agreesTwice() throws Exception {
            String email = newEmail();
            long memberId = streamer(email);
            Cookie session = loggedIn(email);
            agree(session, BOTH).andExpect(status().isNoContent());

            agree(session, BOTH).andExpect(status().isNoContent());

            assertThat(rowsOf(memberId)).isEqualTo(2);
        }

        @Test
        @DisplayName("should keep one row per terms when the streamer sends two agreements at the same time")
        void agreesTwiceAtTheSameTime() throws Exception {
            String email = newEmail();
            long memberId = streamer(email);
            Cookie session = loggedIn(email);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> request = () -> {
                start.await();
                return agree(session, BOTH).andReturn().getResponse().getStatus();
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Integer>> results = List.of(pool.submit(request), pool.submit(request));
                start.countDown();
                for (Future<Integer> result : results) {
                    result.get();
                }
            } finally {
                pool.shutdown();
            }

            assertThat(rowsOf(memberId)).isEqualTo(2);
        }

        @Test
        @DisplayName("should return 400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses CONTENT_GUIDELINE")
        void rejectsMissingContentGuideline() throws Exception {
            String email = newEmail();
            long memberId = streamer(email);
            Cookie session = loggedIn(email);

            agree(session, "{\"terms\":[\"STUDIO_TERMS\"]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("STUDIO_TERMS_REQUIRED"));

            assertThat(rowsOf(memberId)).isZero();
        }

        @Test
        @DisplayName("should return 204 to both requests when the streamer sends two agreements at the same time")
        void bothConcurrentAgreementsSucceed() throws Exception {
            Cookie session = streamerSession();
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> request = () -> {
                start.await();
                return agree(session, BOTH).andReturn().getResponse().getStatus();
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Integer> statuses = new ArrayList<>();
            try {
                List<Future<Integer>> results = List.of(pool.submit(request), pool.submit(request));
                start.countDown();
                for (Future<Integer> result : results) {
                    statuses.add(result.get());
                }
            } finally {
                pool.shutdown();
            }

            assertThat(statuses).containsOnly(204);
        }

        @Test
        @DisplayName("should return 400 with code STUDIO_TERMS_REQUIRED and save nothing when the request misses STUDIO_TERMS")
        void rejectsMissingStudioTerms() throws Exception {
            String email = newEmail();
            long memberId = streamer(email);
            Cookie session = loggedIn(email);

            agree(session, "{\"terms\":[\"CONTENT_GUIDELINE\"]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("STUDIO_TERMS_REQUIRED"));

            assertThat(rowsOf(memberId)).isZero();
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the request has an unknown terms code")
        void rejectsUnknownCode() throws Exception {
            Cookie session = streamerSession();

            agree(session, "{\"terms\":[\"STUDIO_TERMS\",\"CONTENT_GUIDELINE\",\"CLIP_PERMISSION\"]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the terms field is missing")
        void rejectsMissingField() throws Exception {
            Cookie session = streamerSession();

            agree(session, "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed reads the studio channel")
        void blocksStudioChannel() throws Exception {
            Cookie session = streamerSession();

            mockMvc.perform(get("/api/studio/channel").cookie(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("STUDIO_TERMS_NOT_AGREED"));
        }

        @Test
        @DisplayName("should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed changes the title")
        void blocksTitle() throws Exception {
            Cookie session = streamerSession();

            mockMvc.perform(put("/api/studio/title")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .cookie(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "약관 전 제목"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("STUDIO_TERMS_NOT_AGREED"));
        }

        @Test
        @DisplayName("should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed issues a stream key")
        void blocksKeyIssue() throws Exception {
            Cookie session = streamerSession();

            mockMvc.perform(post("/api/studio/stream-key").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("STUDIO_TERMS_NOT_AGREED"));
        }

        @Test
        @DisplayName("should return 403 with code STUDIO_TERMS_NOT_AGREED when a streamer who has not agreed reads the stream key")
        void blocksKeyRead() throws Exception {
            Cookie session = streamerSession();

            mockMvc.perform(get("/api/studio/stream-key").cookie(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("STUDIO_TERMS_NOT_AGREED"));
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN when a member who is not a streamer agrees to the terms")
        void rejectsMember() throws Exception {
            String email = newEmail();
            signUp(email);
            Cookie session = loggedIn(email);

            agree(session, BOTH)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        // 개발 편의상 운영자도 스튜디오를 스트리머와 똑같이 쓴다. 약관 동의도 똑같이 거친다
        @Test
        @DisplayName("should return 204 and then agreed true when an operator agrees to both required terms")
        void operatorAgrees() throws Exception {
            Cookie session = operatorSession();
            agree(session, BOTH).andExpect(status().isNoContent());

            termsStatus(session).andExpect(jsonPath("$.agreed").value(true));
        }

        @Test
        @DisplayName("should return 403 with code STUDIO_TERMS_NOT_AGREED when an operator who has not agreed reads the studio channel")
        void blocksOperatorBeforeAgreeing() throws Exception {
            Cookie session = operatorSession();

            mockMvc.perform(get("/api/studio/channel").cookie(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("STUDIO_TERMS_NOT_AGREED"));
        }

        @Test
        @DisplayName("should let an operator read the studio channel after agreeing to the terms")
        void operatorReadsStudioChannel() throws Exception {
            Cookie session = operatorSession();
            agree(session, BOTH).andExpect(status().isNoContent());

            mockMvc.perform(get("/api/studio/channel").cookie(session)).andExpect(status().isOk());
        }
    }

    // 운영자로 로그인한 세션. 역할은 로그인할 때 세션에 실린다. 로그인 전에 운영자로 바꾼다.
    // 운영자 채널은 앱이 뜰 때 생기므로(OperatorChannelInitializer) 여기서는 직접 불러 만든다
    private Cookie operatorSession() throws Exception {
        String email = newEmail();
        signUp(email);
        jdbcTemplate.update("UPDATE member SET role = 'OPERATOR' WHERE id = ?", memberIdOf(email));
        operatorChannelInitializer.createMissingChannels();
        return loggedIn(email);
    }
}
