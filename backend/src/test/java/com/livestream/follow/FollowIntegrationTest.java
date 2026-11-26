package com.livestream.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.channel.StreamerApplicationReviewService;
import com.livestream.channel.StreamerApplicationService;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
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
// 스트리머는 실제 흐름(가입 → 신청 → 운영자 승인)으로 만든다. DB를 테스트끼리 같이 쓰므로 매번 새로 만든다.
// 동시성 테스트는 테스트 트랜잭션 없이 스레드마다 서비스를 불러 각자 커밋하게 한다(defect-repro)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FollowIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8000";
    private static final String PASSWORD = "abcd1234";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String FOLLOW_PATH = "/api/channels/{channelId}/follow";

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
    private FollowService followService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newEmail() {
        return "f" + unique() + "@example.com";
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
        return memberService.signUp(new SignUpRequest(email, PASSWORD, "팔로" + unique())).id();
    }

    // 가입 → 신청 → 승인. 채널 id를 돌려준다
    private long streamerChannel(String email) {
        long memberId = signUp(email);
        applicationService.apply(memberId, Role.MEMBER);
        long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM streamer_application WHERE member_id = ? AND status = 'PENDING'", Long.class, memberId);
        reviewService.approve(applicationId);
        return jdbcTemplate.queryForObject("SELECT id FROM channel WHERE member_id = ?", Long.class, memberId);
    }

    private long newChannel() {
        return streamerChannel(newEmail());
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

    // 새로 가입한 회원의 로그인 세션
    private Cookie memberSession() throws Exception {
        String email = newEmail();
        signUp(email);
        return loggedIn(email);
    }

    private ResultActions follow(long channelId, Cookie session) throws Exception {
        return mockMvc.perform(put(FOLLOW_PATH, channelId).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session));
    }

    private ResultActions unfollow(long channelId, Cookie session) throws Exception {
        return mockMvc.perform(delete(FOLLOW_PATH, channelId).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session));
    }

    private ResultActions followStatus(long channelId, Cookie session) throws Exception {
        return mockMvc.perform(get(FOLLOW_PATH, channelId).cookie(session));
    }

    // 채널 조회(GET /api/channels/{id}) 응답의 followerCount
    private long followerCountOf(long channelId) throws Exception {
        String body = mockMvc.perform(get("/api/channels/{channelId}", channelId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("followerCount").asLong();
    }

    private long followRowsOf(long channelId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follow WHERE channel_id = ?", Long.class, channelId);
    }

    // threads개 작업을 동시에 출발시킨다. 작업 i는 task.accept(i). 실패는 예외 이름별로 센다(기록용)
    private Map<String, Integer> runAtOnce(int threads, IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<String, Integer> failures = new ConcurrentHashMap<>();
        AtomicInteger success = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.accept(index);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        failures.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("준비되지 않은 스레드가 있다");
            }
            start.countDown();
            if (!done.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("끝나지 않은 스레드가 있다");
            }
        } finally {
            pool.shutdown();
        }
        failures.put("success", success.get());
        return failures;
    }

    @Nested
    @DisplayName("FollowIntegration")
    class Integration {

        @Test
        @DisplayName("should return 204 and then following true when a member follows another channel")
        void followsChannel() throws Exception {
            long channelId = newChannel();
            Cookie session = memberSession();

            follow(channelId, session).andExpect(status().isNoContent());

            followStatus(channelId, session)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.following").value(true));
        }

        @Test
        @DisplayName("should add one to followerCount of the channel lookup when a member follows the channel")
        void addsFollower() throws Exception {
            long channelId = newChannel();
            Cookie session = memberSession();

            follow(channelId, session);

            assertThat(followerCountOf(channelId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should return 204 and then following false when the member unfollows the channel")
        void unfollowsChannel() throws Exception {
            long channelId = newChannel();
            Cookie session = memberSession();
            follow(channelId, session);

            unfollow(channelId, session).andExpect(status().isNoContent());

            followStatus(channelId, session)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.following").value(false));
        }

        @Test
        @DisplayName("should subtract one from followerCount of the channel lookup when the member unfollows the channel")
        void subtractsFollower() throws Exception {
            long channelId = newChannel();
            Cookie first = memberSession();
            Cookie second = memberSession();
            follow(channelId, first);
            follow(channelId, second);

            unfollow(channelId, first);

            assertThat(followerCountOf(channelId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should return followerCount 0 when nobody follows the channel")
        void startsAtZero() throws Exception {
            long channelId = newChannel();

            assertThat(followerCountOf(channelId)).isZero();
        }

        @Test
        @DisplayName("should return 204 and keep followerCount when the member follows the same channel again")
        void keepsCountOnSecondFollow() throws Exception {
            long channelId = newChannel();
            Cookie session = memberSession();
            follow(channelId, session);

            follow(channelId, session).andExpect(status().isNoContent());

            assertThat(followerCountOf(channelId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should keep one follow row when the member follows the same channel again")
        void keepsOneRowOnSecondFollow() throws Exception {
            long channelId = newChannel();
            Cookie session = memberSession();
            follow(channelId, session);

            follow(channelId, session);

            assertThat(followRowsOf(channelId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should return 204 and keep followerCount at 0 when a member who does not follow unfollows the channel")
        void keepsZeroOnUnfollowWithoutFollow() throws Exception {
            long channelId = newChannel();
            Cookie session = memberSession();

            unfollow(channelId, session).andExpect(status().isNoContent());

            assertThat(followerCountOf(channelId)).isZero();
        }

        @Test
        @DisplayName("should make followerCount equal the 10 follow rows when 20 members follow at the same time and 10 of them unfollow at the same time")
        void concurrentFollowsAndUnfollows() throws Exception {
            long channelId = newChannel();
            List<Long> members = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                members.add(signUp(newEmail()));
            }

            Map<String, Integer> follows = runAtOnce(20, i -> followService.follow(members.get(i), channelId));
            Map<String, Integer> unfollows = runAtOnce(10, i -> followService.unfollow(members.get(i), channelId));
            System.out.printf("[defect-repro] 20 follows %s, 10 unfollows %s%n", follows, unfollows);

            assertThat(List.of(followerCountOf(channelId), followRowsOf(channelId))).containsExactly(10L, 10L);
        }

        @Test
        @DisplayName("should make followerCount equal the follow rows when one member sends follows and unfollows to the same channel at the same time many times")
        void concurrentFollowAndUnfollowByOneMember() throws Exception {
            long channelId = newChannel();
            long memberId = signUp(newEmail());

            // 세 개 중 두 개는 팔로우, 하나는 취소. 20개를 동시에 5번.
            // 팔로우·취소 수가 같으면 영향 행 수를 보지 않고 늘 ±1 하는 구현도 합이 0이 되어 통과한다(defect-repro에서 확인)
            for (int round = 0; round < 5; round++) {
                Map<String, Integer> result = runAtOnce(20, i -> {
                    if (i % 3 != 0) {
                        followService.follow(memberId, channelId);
                    } else {
                        followService.unfollow(memberId, channelId);
                    }
                });
                System.out.printf("[defect-repro] one member round %d %s%n", round, result);
            }

            assertThat(followerCountOf(channelId)).isEqualTo(followRowsOf(channelId));
        }

        @Test
        @DisplayName("should return 400 with code CANNOT_FOLLOW_OWN_CHANNEL when the streamer follows their own channel")
        void rejectsOwnChannel() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);

            follow(channelId, session)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CANNOT_FOLLOW_OWN_CHANNEL"));
        }

        @Test
        @DisplayName("should save no follow row and keep followerCount when the streamer follows their own channel")
        void savesNothingForOwnChannel() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);

            follow(channelId, session);

            assertThat(List.of(followRowsOf(channelId), followerCountOf(channelId))).containsExactly(0L, 0L);
        }

        @Test
        @DisplayName("should return 404 with code CHANNEL_NOT_FOUND when a member follows a channel that does not exist")
        void rejectsMissingChannel() throws Exception {
            Cookie session = memberSession();

            follow(999_999_999L, session)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        }

        @Test
        @DisplayName("should return 401 when a visitor who is not logged in follows a channel")
        void rejectsAnonymousFollow() throws Exception {
            long channelId = newChannel();

            mockMvc.perform(put(FOLLOW_PATH, channelId).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 401 when a visitor who is not logged in unfollows a channel")
        void rejectsAnonymousUnfollow() throws Exception {
            long channelId = newChannel();

            mockMvc.perform(delete(FOLLOW_PATH, channelId).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 401 when a visitor who is not logged in asks whether they follow a channel")
        void rejectsAnonymousStatus() throws Exception {
            long channelId = newChannel();

            mockMvc.perform(get(FOLLOW_PATH, channelId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
