package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.channel.StreamerApplicationReviewService;
import com.livestream.channel.StreamerApplicationService;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// 집계는 5초를 기다리지 않고 aggregate()를 직접 부른다(테스트 설정 app.scheduling.enabled=false).
// MediaMTX API는 테스트 설정의 주소(:19997)에 띄운 가짜다. 세션 목록을 페이지로 나눠 주고, 요청 수를 센다.
// Redis·Oracle은 Testcontainers. DB를 테스트끼리 같이 쓰므로 채널은 매번 새로 만들고, 판은 우리 방송 id로만 본다.
// "다음 회차"는 잠금 키를 지워 흉내 낸다. 잠금 만료 자체는 4초를 실제로 기다리는 테스트 하나가 본다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ViewerCountIntegrationTest {

    private static final int FAKE_MEDIAMTX_PORT = 19997;

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
    private BroadcastService broadcastService;

    @Autowired
    private ViewerCounter viewerCounter;

    @Autowired
    private MediaMtxClient mediaMtxClient;

    @Autowired
    private BroadcastMapper broadcastMapper;

    @Autowired
    private ViewerSnapshot viewerSnapshot;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer fakeMediaMtx;
    // 가짜 MediaMTX가 줄 세션 경로. 페이지마다 한 묶음
    private volatile List<List<String>> sessionPages = List.of(List.of());
    private volatile boolean failing;
    private final AtomicInteger listRequests = new AtomicInteger();

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    @BeforeEach
    void startFakeMediaMtx() throws Exception {
        redis.delete(List.of(ViewerSnapshot.LOCK_KEY, ViewerSnapshot.CURRENT_KEY));
        fakeMediaMtx = HttpServer.create(new InetSocketAddress("localhost", FAKE_MEDIAMTX_PORT), 0);
        fakeMediaMtx.createContext("/v3/hlssessions/list", exchange -> {
            listRequests.incrementAndGet();
            if (failing) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            int page = query != null && query.matches(".*\\bpage=\\d+.*")
                    ? Integer.parseInt(query.replaceAll(".*\\bpage=(\\d+).*", "$1")) : 0;
            List<List<String>> pages = sessionPages;
            List<String> items = page < pages.size() ? pages.get(page) : List.of();
            int pageCount = pages.size() == 1 && pages.get(0).isEmpty() ? 0 : pages.size();
            List<String> json = new ArrayList<>();
            for (String path : items) {
                json.add("{\"id\":\"" + unique() + "\",\"created\":\"2026-11-27T22:05:39Z\","
                        + "\"remoteAddr\":\"172.18.0.9:40000\",\"path\":\"" + path + "\",\"query\":\"\","
                        + "\"user\":\"\",\"expiresAt\":null,\"userAgent\":\"test\",\"isCDN\":false,\"outboundBytes\":1}");
            }
            byte[] body = ("{\"itemCount\":" + items.size() + ",\"pageCount\":" + pageCount + ",\"items\":["
                    + String.join(",", json) + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeMediaMtx.start();
    }

    @AfterEach
    void stopFakeMediaMtx() {
        fakeMediaMtx.stop(0);
    }

    // 가입 → 신청 → 승인. 채널 id를 돌려준다
    private long newChannel() {
        long memberId = memberService.signUp(
                new SignUpRequest("v" + unique() + "@example.com", "abcd1234", "시청" + unique())).id();
        applicationService.apply(memberId, Role.MEMBER);
        long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM streamer_application WHERE member_id = ? AND status = 'PENDING'", Long.class, memberId);
        reviewService.approve(applicationId);
        return jdbcTemplate.queryForObject("SELECT id FROM channel WHERE member_id = ?", Long.class, memberId);
    }

    // 방송을 시작하고 그 방송 id를 돌려준다
    private long goLive(long channelId) {
        broadcastService.onAvailable("live/" + channelId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM broadcast WHERE channel_id = ? AND ended_at IS NULL", Long.class, channelId);
    }

    private static List<String> sessions(long channelId, int count) {
        return Collections.nCopies(count, "live/" + channelId);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    // 다음 회차: 이번 회차 잠금이 풀린 것처럼 한다
    private void nextRound() {
        redis.delete(ViewerSnapshot.LOCK_KEY);
    }

    private String currentCountKey() {
        return "viewers:" + redis.opsForValue().get(ViewerSnapshot.CURRENT_KEY) + ":count";
    }

    // 지금 판(방송ID → 수)
    private Map<Object, Object> currentSnapshot() {
        return redis.opsForHash().entries(currentCountKey());
    }

    private long viewerCountOf(long channelId) throws Exception {
        String body = mockMvc.perform(get("/api/channels/" + channelId)).andReturn().getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("viewerCount").asLong();
    }

    @Nested
    @DisplayName("ViewerCountIntegration")
    class Integration {

        @Test
        @DisplayName("should store A=3 and B=1 by broadcast id without C in the current snapshot when live A, live B and offline C have 3, 1 and 2 sessions")
        void storesLiveCountsByBroadcastId() {
            long a = newChannel();
            long b = newChannel();
            long c = newChannel();
            long broadcastA = goLive(a);
            long broadcastB = goLive(b);
            // C는 방송했다가 끝났다. 끝난 방송은 판에 없어야 한다
            long endedC = goLive(c);
            broadcastService.onUnavailable("live/" + c);
            sessionPages = List.of(concat(concat(sessions(a, 3), sessions(b, 1)), sessions(c, 2)));

            viewerCounter.aggregate();

            assertThat(currentSnapshot()).containsEntry(String.valueOf(broadcastA), "3")
                    .containsEntry(String.valueOf(broadcastB), "1")
                    .doesNotContainKey(String.valueOf(endedC));
        }

        @Test
        @DisplayName("should return viewerCount 3 from the channel lookup of A after aggregating 3 sessions on A")
        void returnsViewerCountFromChannelLookup() throws Exception {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 3));

            viewerCounter.aggregate();

            assertThat(viewerCountOf(a)).isEqualTo(3);
        }

        @Test
        @DisplayName("should return viewerCount 0 from the channel lookup of a channel that is not live even when its path has sessions")
        void returnsZeroForOfflineChannel() throws Exception {
            long offline = newChannel();
            sessionPages = List.of(sessions(offline, 2));

            viewerCounter.aggregate();

            assertThat(viewerCountOf(offline)).isZero();
        }

        @Test
        @DisplayName("should write a new snapshot in the next round after a round in which MediaMTX failed")
        void recoversAfterFailedRound() throws Exception {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 3));
            viewerCounter.aggregate();
            nextRound();
            failing = true;
            viewerCounter.aggregate();
            nextRound();
            failing = false;
            sessionPages = List.of(sessions(a, 1));

            viewerCounter.aggregate();

            assertThat(viewerCountOf(a)).isEqualTo(1);
        }

        @Test
        @DisplayName("should store 0 for live channel D with no session in the current snapshot")
        void storesZeroForLiveChannelWithoutSessions() {
            long d = newChannel();
            long broadcastD = goLive(d);
            sessionPages = List.of(List.of());

            viewerCounter.aggregate();

            assertThat(currentSnapshot()).containsEntry(String.valueOf(broadcastD), "0");
        }

        @Test
        @DisplayName("should count the sessions from both pages when MediaMTX returns the session list in 2 pages")
        void countsSessionsFromEveryPage() throws Exception {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 2), sessions(a, 1));

            viewerCounter.aggregate();

            assertThat(viewerCountOf(a)).isEqualTo(3);
        }

        @Test
        @DisplayName("should read the MediaMTX session list once when two counters aggregate at the same time")
        void readsSessionListOnceForTwoServers() throws Exception {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 1));
            // 서버 2대: 같은 Redis·DB·MediaMTX를 보는 집계 객체 두 개
            ViewerCounter otherServer = new ViewerCounter(mediaMtxClient, broadcastMapper, viewerSnapshot);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> rounds = new ArrayList<>();
                for (ViewerCounter counter : List.of(viewerCounter, otherServer)) {
                    rounds.add(pool.submit(() -> {
                        start.await();
                        counter.aggregate();
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> round : rounds) {
                    round.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(listRequests.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should not read the session list again when a second counter runs before the lock expires")
        void skipsSecondCounterBeforeLockExpires() {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 1));
            ViewerCounter otherServer = new ViewerCounter(mediaMtxClient, broadcastMapper, viewerSnapshot);
            viewerCounter.aggregate();

            otherServer.aggregate();

            assertThat(listRequests.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should hold the round lock for at most 4 seconds")
        void holdsLockForFourSeconds() {
            sessionPages = List.of(List.of());

            viewerCounter.aggregate();

            assertThat(redis.getExpire(ViewerSnapshot.LOCK_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, 4000L);
        }

        @Test
        @DisplayName("should let the other counter aggregate in the next round after the lock of the stopped counter expires")
        void otherCounterTakesOverAfterLockExpires() {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 1));
            ViewerCounter otherServer = new ViewerCounter(mediaMtxClient, broadcastMapper, viewerSnapshot);
            // 이 서버가 한 회차를 센 뒤 멈췄다. 잠금은 지우지 않고 만료를 기다린다
            viewerCounter.aggregate();
            await().atMost(Duration.ofSeconds(6)).pollInterval(Duration.ofMillis(100))
                    .until(() -> !redis.hasKey(ViewerSnapshot.LOCK_KEY));

            otherServer.aggregate();

            assertThat(listRequests.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("should make the snapshot expire 2 minutes after it is written")
        void expiresSnapshotAfterTwoMinutes() {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 1));

            viewerCounter.aggregate();

            assertThat(redis.getExpire(currentCountKey(), TimeUnit.SECONDS)).isBetween(110L, 120L);
        }

        @Test
        @DisplayName("should return viewerCount 0 for a live channel when the current snapshot has expired")
        void returnsZeroWhenSnapshotExpired() throws Exception {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 3));
            viewerCounter.aggregate();
            // 2분이 지난 것처럼 판을 바로 만료시킨다. 새 판은 만들지 않는다(집계가 멈춤)
            String countKey = currentCountKey();
            redis.expire(countKey, Duration.ofMillis(1));
            await().atMost(Duration.ofSeconds(2)).until(() -> !redis.hasKey(countKey));

            assertThat(viewerCountOf(a)).isZero();
        }

        @Test
        @DisplayName("should return viewerCount 0 for a live channel when no snapshot has been made")
        void returnsZeroWithoutSnapshot() throws Exception {
            long a = newChannel();
            goLive(a);

            assertThat(viewerCountOf(a)).isZero();
        }

        @Test
        @DisplayName("should keep viewerCount of the previous snapshot when MediaMTX fails in the next round")
        void keepsPreviousSnapshotWhenMediaMtxFails() throws Exception {
            long a = newChannel();
            goLive(a);
            sessionPages = List.of(sessions(a, 3));
            viewerCounter.aggregate();
            nextRound();
            failing = true;

            viewerCounter.aggregate();

            assertThat(viewerCountOf(a)).isEqualTo(3);
        }
    }
}
