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
            js_import slow from /etc/nginx/slow.js;
            server {
                listen 8080;
                access_log /dev/stdout count;
                default_type application/json;
                location = /api/channels/404 {
                    return 404 '{"code":"CHANNEL_NOT_FOUND"}';
                }
                location = /api/channels/500 {
                    return 500 '{"code":"INTERNAL_ERROR"}';
                }
                # 동시 요청 시험용 느린 Spring(SLOW_JS, 300ms). 빠르면 동시에 보낸 요청이 실제로는 앞 응답이 캐시된 뒤에
                # 도착해 잠금이 없어도 한 번만 간다. 1초 캐시보다 길면 기다리던 요청이 만료된 항목을 보고 다시 물어 두 번이 된다
                location ~ ^/api/channels/(9|10)$ {
                    js_content slow.answer;
                }
                location / {
                    add_header Cache-Control "no-cache, no-store, max-age=0, must-revalidate";
                    add_header Expires 0;
                    return 200 '{"answer":"$request_id"}';
                }
            }
            """;

    // 가짜 Spring의 바깥 설정. 이미지에 들어 있는 njs 모듈을 켠다(느린 응답을 정확한 시간만큼 늦추려고)
    private static final String FAKE_SPRING_MAIN_CONF = """
            load_module modules/ngx_http_js_module.so;
            events {}
            http {
                include /etc/nginx/conf.d/*.conf;
            }
            """;

    private static final String SLOW_JS = """
            function answer(r) {
                setTimeout(() => {
                    r.headersOut['Content-Type'] = 'application/json';
                    r.headersOut['Cache-Control'] = 'no-cache, no-store, max-age=0, must-revalidate';
                    r.headersOut['Expires'] = '0';
                    r.return(200, JSON.stringify({ answer: r.variables.request_id }));
                }, 300);
            }
            export default { answer };
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
                .withCopyToContainer(Transferable.of(FAKE_SPRING_MAIN_CONF), "/etc/nginx/nginx.conf")
                .withCopyToContainer(Transferable.of(SLOW_JS), "/etc/nginx/slow.js")
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

    // 시청자 여럿이 같은 순간에 GET을 보낸다(래치로 한꺼번에 출발). 응답 상태 코드를 돌려준다
    private static List<Integer> concurrentGets(String path, int viewers) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(viewers);
        try {
            List<Future<HttpResponse<String>>> answers = new ArrayList<>();
            for (int i = 0; i < viewers; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return get(path);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> answer : answers) {
                statuses.add(answer.get().statusCode());
            }
            return statuses;
        } finally {
            pool.shutdown();
        }
    }

    // 가짜 Spring이 받은 요청 중 "메서드 경로"로 시작하는 줄 수(query가 붙은 것도 센다).
    // 컨테이너 로그는 조금 늦게 넘어온다. 고정 시간만 기다리면 늦게 온 줄을 빼고 세어 "1번"이 거짓으로 통과할 수 있어,
    // 줄 수가 300ms 동안 더 늘지 않을 때까지 기다린다(최대 3초)
    private static long springCalls(String method, String path) throws InterruptedException {
        long previous = -1;
        long current = countLogLines(method, path);
        for (int i = 0; i < 10 && current != previous; i++) {
            Thread.sleep(300);
            previous = current;
            current = countLogLines(method, path);
        }
        return current;
    }

    private static long countLogLines(String method, String path) {
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
        @DisplayName("should return a fresh answer from Spring when the same channel is requested after 1 second")
        void returnsFreshAnswerAfterOneSecond() throws Exception {
            String first = get("/api/channels/107").body();
            Thread.sleep(2100);

            String second = get("/api/channels/107").body();

            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("should still answer from cache when the same channel is requested 900ms later")
        void answersFromCacheAfter900ms() throws Exception {
            get("/api/channels/108");
            // 캐시 시각이 초 단위라 1초 캐시는 적어도 1초 남는다. 900ms 뒤는 늘 캐시 안이다
            Thread.sleep(900);

            get("/api/channels/108");

            assertThat(springCalls("GET", "/api/channels/108")).isEqualTo(1);
        }

        @Test
        @DisplayName("should answer every concurrent viewer with 200 when the uncached channel is requested at the same time")
        void answersEveryConcurrentViewer() throws Exception {
            List<Integer> statuses = concurrentGets("/api/channels/10", 20);

            assertThat(statuses).containsOnly(200);
        }

        @Test
        @DisplayName("should ask Spring once when 20 viewers request the same uncached channel at the same time")
        void asksSpringOnceForConcurrentMisses() throws Exception {
            concurrentGets("/api/channels/9", 20);

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
        @DisplayName("should pass a channel id with leading zeros to Spring without caching it")
        void passesLeadingZeroIds() throws Exception {
            // Spring은 0110을 채널 110으로 읽지만 캐시 키는 따로라, 담으면 변형마다 캐시를 채운다(#29 보안 점검)
            get("/api/channels/0110");
            get("/api/channels/0110");

            assertThat(springCalls("GET", "/api/channels/0110")).isEqualTo(2);
        }

        @Test
        @DisplayName("should not cache a server error response for a channel")
        void doesNotCacheServerErrors() throws Exception {
            get("/api/channels/500");
            get("/api/channels/500");

            assertThat(springCalls("GET", "/api/channels/500")).isEqualTo(2);
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
