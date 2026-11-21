package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.TestcontainersConfiguration;
import com.livestream.channel.StreamerApplicationReviewService;
import com.livestream.channel.StreamerApplicationService;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 훅은 MediaMTX가 보내는 그대로(Origin·세션 없음) 보안 필터를 모두 거쳐 부른다. 채널 상태는 공개 채널 API로 본다.
// 대조는 1분을 기다리지 않고 reconcile()을 직접 부른다. 그때 MediaMTX API는 테스트 설정의 주소(:19997)에 띄운 가짜다.
// 채널은 실제 흐름(가입 → 신청 → 승인)으로 만든다. DB를 테스트끼리 같이 쓰므로 매번 새로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BroadcastIntegrationTest {

    private static final int FAKE_MEDIAMTX_PORT = 19997;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberService memberService;

    @Autowired
    private StreamerApplicationService applicationService;

    @Autowired
    private StreamerApplicationReviewService reviewService;

    @Autowired
    private BroadcastService broadcastService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    // 가입 → 신청 → 승인. 채널 id를 돌려준다
    private long newChannel() {
        long memberId = memberService.signUp(
                new SignUpRequest("b" + unique() + "@example.com", "abcd1234", "방송" + unique())).id();
        applicationService.apply(memberId, Role.MEMBER);
        long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM streamer_application WHERE member_id = ? AND status = 'PENDING'", Long.class, memberId);
        reviewService.approve(applicationId);
        return jdbcTemplate.queryForObject("SELECT id FROM channel WHERE member_id = ?", Long.class, memberId);
    }

    private ResultActions hook(String event, String path) throws Exception {
        return mockMvc.perform(post("/internal/mediamtx/" + event).param("path", path));
    }

    private int liveCount(long channelId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM broadcast WHERE channel_id = ? AND ended_at IS NULL", Integer.class, channelId);
    }

    private ResultActions channelPage(long channelId) throws Exception {
        return mockMvc.perform(get("/api/channels/" + channelId));
    }

    // 가짜 MediaMTX API를 띄운 채로 대조를 한 번 돌린다. pathsJson은 /v3/paths/list의 items 배열 안쪽
    private void reconcileWith(String pathsJson) throws Exception {
        byte[] body = ("{\"pageCount\":1,\"itemCount\":1,\"items\":[" + pathsJson + "]}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer fakeMediaMtx = HttpServer.create(new InetSocketAddress("localhost", FAKE_MEDIAMTX_PORT), 0);
        fakeMediaMtx.createContext("/v3/paths/list", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeMediaMtx.start();
        try {
            broadcastService.reconcile();
        } finally {
            fakeMediaMtx.stop(0);
        }
    }

    private static String availablePath(String name) {
        return "{\"name\":\"" + name + "\",\"confName\":\"all_others\",\"ready\":true,\"available\":true,"
                + "\"online\":true,\"source\":{\"type\":\"rtmpConn\",\"id\":\"x\"},\"readers\":[]}";
    }

    @Nested
    @DisplayName("BroadcastIntegration")
    class Integration {

        @Test
        @DisplayName("should create a live broadcast with a start time and show the channel as live when the available notice for live and the channel id arrives")
        void startsBroadcastOnAvailable() throws Exception {
            long channelId = newChannel();

            hook("available", "live/" + channelId).andExpect(status().isNoContent());

            Map<String, Object> broadcast = jdbcTemplate.queryForMap(
                    "SELECT started_at, ended_at FROM broadcast WHERE channel_id = ?", channelId);
            assertThat(broadcast.get("STARTED_AT")).isNotNull();
            assertThat(broadcast.get("ENDED_AT")).isNull();
            channelPage(channelId).andExpect(jsonPath("$.live").value(true));
        }

        @Test
        @DisplayName("should keep one live broadcast when the same available notice arrives twice")
        void keepsOneBroadcastOnRepeatedNotice() throws Exception {
            long channelId = newChannel();

            hook("available", "live/" + channelId).andExpect(status().isNoContent());
            hook("available", "live/" + channelId).andExpect(status().isNoContent());

            assertThat(liveCount(channelId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should stamp the end time and show the channel as offline when the unavailable notice arrives")
        void endsBroadcastOnUnavailable() throws Exception {
            long channelId = newChannel();
            hook("available", "live/" + channelId).andExpect(status().isNoContent());

            hook("unavailable", "live/" + channelId).andExpect(status().isNoContent());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM broadcast WHERE channel_id = ? AND ended_at IS NOT NULL",
                    Integer.class, channelId)).isEqualTo(1);
            channelPage(channelId).andExpect(jsonPath("$.live").value(false));
        }

        @Test
        @DisplayName("should not start a broadcast when the available notice is for 720 and the channel id")
        void ignoresAvailableOutsideLive() throws Exception {
            long channelId = newChannel();

            hook("available", "720/" + channelId).andExpect(status().isNoContent());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM broadcast WHERE channel_id = ?", Integer.class, channelId)).isZero();
        }

        @Test
        @DisplayName("should not end the live broadcast when the unavailable notice is for 720 and the channel id")
        void ignoresUnavailableOutsideLive() throws Exception {
            long channelId = newChannel();
            hook("available", "live/" + channelId).andExpect(status().isNoContent());

            hook("unavailable", "720/" + channelId).andExpect(status().isNoContent());

            assertThat(liveCount(channelId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should start the broadcast when reconcile finds the channel available in MediaMTX without a live broadcast")
        void reconcileStartsMissingBroadcast() throws Exception {
            long channelId = newChannel();

            reconcileWith(availablePath("live/" + channelId));

            assertThat(liveCount(channelId)).isEqualTo(1);
        }

        @Test
        @DisplayName("should end the broadcast when reconcile finds it live in the database but not in MediaMTX")
        void reconcileEndsStaleBroadcast() throws Exception {
            long channelId = newChannel();
            hook("available", "live/" + channelId).andExpect(status().isNoContent());

            reconcileWith(availablePath("720/" + channelId));

            assertThat(liveCount(channelId)).isZero();
        }

        // defect-repro 골격: 모든 스레드가 준비된 뒤 한꺼번에 시작 처리 함수를 부른다(서버 2대 이상이 같은 채널을 동시에 시작 처리).
        // 스레드 수는 커넥션 풀(Hikari 기본 10)보다 작게 둔다
        @Test
        @DisplayName("should keep one live broadcast when two servers start the same channel at the same time")
        void keepsOneBroadcastUnderConcurrentStarts() throws Exception {
            long channelId = newChannel();
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        broadcastService.onAvailable("live/" + channelId);
                    } catch (Exception e) {
                        // 시작 처리가 던진 예외는 세지 않는다. 결과는 DB의 진행 중 방송 수로만 본다
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("준비되지 않은 스레드가 있다");
            }
            start.countDown();
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("끝나지 않은 스레드가 있다");
            }
            pool.shutdown();

            assertThat(liveCount(channelId)).isEqualTo(1);
        }
    }
}
