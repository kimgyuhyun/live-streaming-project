package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

// 채널 조회 1초 캐시(#29)는 실제 nginx로 본다. 요점이 "Spring까지 몇 번 갔나"라 설정 글자로는 증명할 수 없다.
// 저장소의 infra/nginx/nginx.conf를 그대로 넣은 입구 nginx 뒤에, 가짜 Spring(nginx 하나에 api-1·api-2·mediamtx 별칭)을 둔다.
// 가짜 Spring은 요청마다 다른 $request_id를 돌려주고, 받은 요청을 "메서드 URI" 한 줄씩 로그에 남긴다 — 그 줄 수가 Spring까지 간 횟수다.
// 테스트끼리 캐시를 같이 쓰므로 테스트마다 다른 채널 id를 쓴다
class NginxChannelCacheTest {

    private static final String IMAGE = "nginx:1.30.7-alpine";   // infra/nginx/Dockerfile과 같은 이미지
    // gradle은 backend/에서 테스트를 돌린다
    private static final Path NGINX_CONF = Path.of("..", "infra", "nginx", "nginx.conf");

    // Spring Security처럼 모든 응답에 no-store를 붙인다. 입구 nginx가 이것을 무시해야 담을 수 있다
    private static final String FAKE_SPRING_CONF = """
            log_format count '$request_method $request_uri';
            server {
                listen 8080;
                access_log /dev/stdout count;
                default_type application/json;
                location = /api/channels/404 {
                    return 404 '{"code":"CHANNEL_NOT_FOUND"}';
                }
                # 동시 요청 시험용 느린 Spring. limit_rate는 첫 1초 몫(1KB)을 바로 보내므로 1.5KB를 줘 약 0.5초 걸리게 한다.
                # 빠르면 동시에 보낸 요청이 실제로는 앞 응답이 캐시된 뒤에 도착해 잠금이 없어도 한 번만 간다
                location = /api/channels/9 {
                    limit_rate 1k;
                    add_header Cache-Control "no-cache, no-store, max-age=0, must-revalidate";
                    add_header Expires 0;
                    return 200 '{"answer":"$request_id","pad":"%s"}';
                }""".formatted("x".repeat(1500)) + """

                location / {
                    add_header Cache-Control "no-cache, no-store, max-age=0, must-revalidate";
                    add_header Expires 0;
                    return 200 '{"answer":"$request_id"}';
                }
            }
            """;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Network network;
    private static GenericContainer<?> fakeSpring;
    private static GenericContainer<?> edge;

    @BeforeAll
    static void startNginx() {
        network = Network.newNetwork();
        fakeSpring = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases("api-1", "api-2", "mediamtx")
                .withCopyToContainer(Transferable.of(FAKE_SPRING_CONF), "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(8080)
                .waitingFor(Wait.forListeningPort());
        fakeSpring.start();
        edge = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withCopyToContainer(MountableFile.forHostPath(NGINX_CONF), "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(80)
                .waitingFor(Wait.forListeningPort());
        edge.start();
    }

    @AfterAll
    static void stopNginx() {
        edge.stop();
        fakeSpring.stop();
        network.close();
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        URI uri = URI.create("http://" + edge.getHost() + ":" + edge.getMappedPort(80) + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method(method, method.equals("PUT")
                        ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path);
    }

    // 가짜 Spring이 받은 요청 중 "메서드 경로"로 시작하는 줄 수(query가 붙은 것도 센다)
    private static long springCalls(String method, String path) throws InterruptedException {
        Thread.sleep(300);   // 컨테이너 로그가 넘어오기를 잠깐 기다린다
        String prefix = method + " " + path;
        return fakeSpring.getLogs().lines()
                .map(String::strip)
                .filter(line -> line.equals(prefix) || line.startsWith(prefix + "?"))
                .count();
    }

    @Nested
    @DisplayName("nginx channel cache")
    class ChannelCache {

        @Test
        @DisplayName("should answer a second request for the same channel within 1 second without asking Spring again")
        void answersFromCacheWithinOneSecond() throws Exception {
            get("/api/channels/101");
            get("/api/channels/101");

            assertThat(springCalls("GET", "/api/channels/101")).isEqualTo(1);
        }

        @Test
        @DisplayName("should ask Spring again when the same channel is requested after 1 second")
        void asksSpringAgainAfterOneSecond() throws Exception {
            get("/api/channels/102");
            // nginx의 캐시 시각은 초 단위라 1초 캐시가 1~2초 남는다. 2초를 넘겨 확실히 지나게 한다
            Thread.sleep(2100);

            get("/api/channels/102");

            assertThat(springCalls("GET", "/api/channels/102")).isEqualTo(2);
        }

        @Test
        @DisplayName("should ask Spring once when 20 viewers request the same uncached channel at the same time")
        void asksSpringOnceForConcurrentMisses() throws Exception {
            int viewers = 20;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(viewers);
            try {
                List<Future<HttpResponse<String>>> answers = new ArrayList<>();
                for (int i = 0; i < viewers; i++) {
                    answers.add(pool.submit(() -> {
                        start.await();
                        return get("/api/channels/9");
                    }));
                }
                start.countDown();
                for (Future<HttpResponse<String>> answer : answers) {
                    answer.get();
                }
            } finally {
                pool.shutdown();
            }

            assertThat(springCalls("GET", "/api/channels/9")).isEqualTo(1);
        }

        @Test
        @DisplayName("should keep a separate cache for each channel")
        void keepsSeparateCachePerChannel() throws Exception {
            String first = get("/api/channels/103").body();

            String second = get("/api/channels/104").body();

            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("should serve the cached answer when the same channel is requested with a different query string")
        void ignoresQueryString() throws Exception {
            get("/api/channels/105?x=1");
            get("/api/channels/105?x=2");

            assertThat(springCalls("GET", "/api/channels/105")).isEqualTo(1);
        }

        @Test
        @DisplayName("should pass every request for the studio channel to Spring")
        void passesStudioChannel() throws Exception {
            get("/api/studio/channel");
            get("/api/studio/channel");

            assertThat(springCalls("GET", "/api/studio/channel")).isEqualTo(2);
        }

        @Test
        @DisplayName("should pass every request for the logged-in member to Spring")
        void passesMe() throws Exception {
            get("/api/members/me");
            get("/api/members/me");

            assertThat(springCalls("GET", "/api/members/me")).isEqualTo(2);
        }

        @Test
        @DisplayName("should pass every channel update to Spring")
        void passesChannelUpdate() throws Exception {
            send("PUT", "/api/channels/106");
            send("PUT", "/api/channels/106");

            assertThat(springCalls("PUT", "/api/channels/106")).isEqualTo(2);
        }

        @Test
        @DisplayName("should not cache an error response for a channel")
        void doesNotCacheErrors() throws Exception {
            get("/api/channels/404");
            get("/api/channels/404");

            assertThat(springCalls("GET", "/api/channels/404")).isEqualTo(2);
        }
    }
}
