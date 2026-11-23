package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ContainerNetwork;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

// IP당 요청 수 제한(#31)은 실제 nginx로 본다. NginxChannelCacheTest와 같은 틀이다.
// 저장소의 infra/nginx/nginx.conf를 그대로 넣은 입구 nginx 뒤에 가짜 Spring(nginx 하나에 api-1·api-2·mediamtx 별칭)을 둔다.
// 요청은 호스트가 아니라 컨테이너 안의 curl로 보낸다. 출처 주소를 골라야 하기 때문이다.
//   edge 망(10.250.0.0/24, server-infra의 edge 대역)에서 보낸 X-Forwarded-For는 믿어야 하고,
//   outside 망에서 보낸 X-Forwarded-For는 믿지 않아야 한다.
// 가짜 Spring은 받은 요청을 "메서드 URI X-Forwarded-For" 한 줄씩 남긴다. 입구 nginx는 실제 사용자 IP를 X-Forwarded-For로 넘기므로
// 사용자 IP별로 Spring까지 간 횟수를 셀 수 있다.
// 테스트끼리 제한 영역을 같이 쓰므로 테스트마다 다른 사용자 IP를 쓴다(198.51.100.0/24, 문서용 대역)
class NginxRateLimitTest {

    private static final String IMAGE = "nginx:1.30.7-alpine";   // infra/nginx/Dockerfile과 같은 이미지. curl이 들어 있다
    // gradle은 backend/에서 테스트를 돌린다
    private static final Path NGINX_CONF = Path.of("..", "infra", "nginx", "nginx.conf");
    private static final String EDGE_SUBNET = "10.250.0.0/24";
    private static final String ENTRY = "http://entry";

    // 8888은 MediaMTX(HLS) 자리다. 로그인은 Spring의 로그인 시도 제한(#3)처럼 429를 준다
    private static final String FAKE_SPRING_CONF = """
            log_format count '$request_method $request_uri $http_x_forwarded_for';
            server {
                listen 8080;
                listen 8888;
                access_log /dev/stdout count;
                default_type application/json;
                location = /api/auth/login {
                    return 429 '{"code":"MEMBER_LOGIN_BLOCKED","message":"login blocked"}';
                }
                location / {
                    return 200 '{"answer":"$request_id"}';
                }
            }
            """;

    private static Network edgeNetwork;
    private static Network outsideNetwork;
    private static GenericContainer<?> fakeSpring;
    private static GenericContainer<?> entry;
    private static GenericContainer<?> edgeClient;
    private static GenericContainer<?> outsideClient;

    @BeforeAll
    static void startNginx() {
        edgeNetwork = Network.builder()
                .createNetworkCmdModifier(cmd -> cmd.withIpam(new com.github.dockerjava.api.model.Network.Ipam()
                        .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet(EDGE_SUBNET))))
                .build();
        outsideNetwork = Network.newNetwork();

        fakeSpring = new GenericContainer<>(IMAGE)
                .withNetwork(outsideNetwork)
                .withNetworkAliases("api-1", "api-2", "mediamtx")
                .withCopyToContainer(Transferable.of(FAKE_SPRING_CONF), "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(8080)
                .waitingFor(Wait.forListeningPort());
        fakeSpring.start();

        // nginx는 뜰 때 /hls/의 mediamtx 이름을 찾는다. 가짜 Spring과 같은 망에서 먼저 띄우고 edge 망에 잇는다
        entry = new GenericContainer<>(IMAGE)
                .withNetwork(outsideNetwork)
                .withNetworkAliases("entry")
                .withCopyToContainer(MountableFile.forHostPath(NGINX_CONF), "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(80)
                .waitingFor(Wait.forListeningPort());
        entry.start();
        DockerClientFactory.instance().client().connectToNetworkCmd()
                .withContainerId(entry.getContainerId())
                .withNetworkId(edgeNetwork.getId())
                .withContainerNetwork(new ContainerNetwork().withAliases("entry"))
                .exec();

        edgeClient = client(edgeNetwork);
        outsideClient = client(outsideNetwork);
    }

    // curl만 쓰는 컨테이너. 명령이 nginx가 아니면 이미지의 진입 스크립트가 그 명령을 그대로 실행한다
    private static GenericContainer<?> client(Network network) {
        GenericContainer<?> client = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withCommand("sleep", "infinity");
        client.start();
        return client;
    }

    @AfterAll
    static void stopNginx() {
        outsideClient.stop();
        edgeClient.stop();
        entry.stop();
        fakeSpring.stop();
        outsideNetwork.close();
        edgeNetwork.close();
    }

    // 요청을 curl 하나로 잇달아 보낸다(연결을 다시 쓰므로 수십 건이 1초 안에 간다). 요청마다 X-Forwarded-For를 정한다(null이면 붙이지 않는다).
    // 응답 상태 코드를 보낸 순서대로 돌려준다
    private static List<Integer> sendAll(GenericContainer<?> client, String method, String path, List<String> forwardedFors)
            throws Exception {
        List<String> command = new ArrayList<>(List.of("curl"));
        for (int i = 0; i < forwardedFors.size(); i++) {
            if (i > 0) {
                command.add("--next");
            }
            command.addAll(List.of("-s", "-o", "/dev/null", "-w", "%{http_code}\\n", "-X", method));
            if (forwardedFors.get(i) != null) {
                command.addAll(List.of("-H", "X-Forwarded-For: " + forwardedFors.get(i)));
            }
            command.add(ENTRY + path);
        }
        ExecResult result = client.execInContainer(command.toArray(String[]::new));
        return result.getStdout().lines().map(Integer::valueOf).toList();
    }

    // 한 사용자 IP가 같은 경로로 n건을 한꺼번에 보낸다
    private static List<Integer> burst(GenericContainer<?> client, String forwardedFor, String path, int n) throws Exception {
        return sendAll(client, "GET", path, Collections.nCopies(n, forwardedFor));
    }

    // 응답 본문 하나
    private static String body(GenericContainer<?> client, String method, String forwardedFor, String path) throws Exception {
        return client.execInContainer("curl", "-s", "-X", method, "-H", "X-Forwarded-For: " + forwardedFor, ENTRY + path)
                .getStdout();
    }

    // 가짜 Spring이 이 사용자 IP로 받은 요청 수. 컨테이너 로그는 조금 늦게 넘어오므로 줄 수가 300ms 동안 더 늘지 않을 때까지 기다린다(최대 3초)
    private static long springCalls(String forwardedFor) throws InterruptedException {
        long previous = -1;
        long current = countLogLines(forwardedFor);
        for (int i = 0; i < 10 && current != previous; i++) {
            Thread.sleep(300);
            previous = current;
            current = countLogLines(forwardedFor);
        }
        return current;
    }

    private static long countLogLines(String forwardedFor) {
        return fakeSpring.getLogs().lines()
                .map(String::strip)
                .filter(line -> line.endsWith(" " + forwardedFor))
                .count();
    }

    @Nested
    @DisplayName("nginx rate limit")
    class RateLimit {

        @Test
        @DisplayName("should answer every request with 200 when 50 viewers behind one IP poll at 10 requests per second for 5 seconds")
        void allowsPollingBehindOneIp() throws Exception {
            // 시청자 50명 × 5초에 한 번 = 초당 10건. 0.1초 간격으로 50건(5초)
            String script = "for i in $(seq 50); do "
                    + "curl -s -o /dev/null -w '%{http_code}\\n' -H 'X-Forwarded-For: 198.51.100.1' " + ENTRY + "/api/channels/21 & "
                    + "sleep 0.1; done; wait";

            List<Integer> statuses = edgeClient.execInContainer("sh", "-c", script).getStdout().lines()
                    .map(Integer::valueOf).toList();

            assertThat(statuses).hasSize(50).containsOnly(200);
        }

        @Test
        @DisplayName("should count requests by the X-Forwarded-For address when they come from the edge range")
        void countsByForwardedForFromEdge() throws Exception {
            // 같은 edge에서 온 사용자 A가 한도를 넘겨도
            burst(edgeClient, "198.51.100.2", "/api/channels/0021", 60);

            List<Integer> statuses = burst(edgeClient, "198.51.100.3", "/api/channels/0021", 1);

            assertThat(statuses).containsOnly(200);
        }

        @Test
        @DisplayName("should not limit HLS requests when one IP sends more than the limit")
        void doesNotLimitHls() throws Exception {
            List<Integer> statuses = burst(edgeClient, "198.51.100.4", "/hls/live/1/index.m3u8", 60);

            assertThat(statuses).hasSize(60).containsOnly(200);
        }

        @Test
        @DisplayName("should answer every request with 200 when one IP sends 40 requests at once")
        void allowsBurstOfForty() throws Exception {
            List<Integer> statuses = burst(edgeClient, "198.51.100.5", "/api/channels/0021", 40);

            assertThat(statuses).hasSize(40).containsOnly(200);
        }

        @Test
        @DisplayName("should reject requests for a cached channel with 429 when one IP goes over the limit")
        void limitsCachedChannel() throws Exception {
            List<Integer> statuses = burst(edgeClient, "198.51.100.6", "/api/channels/21", 60);

            assertThat(statuses).contains(429);
        }

        @Test
        @DisplayName("should answer 429 when one IP sends 60 requests at once to the public API")
        void rejectsOverLimit() throws Exception {
            List<Integer> statuses = burst(edgeClient, "198.51.100.7", "/api/channels/0021", 60);

            assertThat(statuses).contains(429);
        }

        @Test
        @DisplayName("should not pass rejected requests to Spring when one IP goes over the limit")
        void keepsRejectedFromSpring() throws Exception {
            List<Integer> statuses = burst(edgeClient, "198.51.100.8", "/api/channels/0021", 60);
            long answered = statuses.stream().filter(status -> status == 200).count();

            // Spring까지 간 것은 200으로 답한 것뿐이고, 그래서 60건 전부는 아니다
            assertThat(springCalls("198.51.100.8")).isEqualTo(answered).isLessThan(60);
        }

        @Test
        @DisplayName("should answer the rate limit 429 with a JSON body whose code is RATE_LIMITED")
        void answersRateLimitedBody() throws Exception {
            burst(edgeClient, "198.51.100.9", "/api/channels/0021", 60);

            String body = body(edgeClient, "GET", "198.51.100.9", "/api/channels/0021");

            assertThat(body).contains("\"code\":\"RATE_LIMITED\"");
        }

        @Test
        @DisplayName("should keep answering another IP with 200 when one IP goes over the limit")
        void keepsOtherIpsAnswered() throws Exception {
            // outside 망의 클라이언트(자기 주소로 센다)가 한도를 넘겨도
            sendAll(outsideClient, "GET", "/api/channels/0021", Collections.nCopies(60, null));

            List<Integer> statuses = burst(edgeClient, "198.51.100.10", "/api/channels/0021", 1);

            assertThat(statuses).containsOnly(200);
        }

        @Test
        @DisplayName("should ignore X-Forwarded-For sent from outside the edge range")
        void ignoresForwardedForFromOutside() throws Exception {
            // edge가 아닌 곳에서 요청마다 다른 X-Forwarded-For를 붙여도 한 IP로 센다
            List<String> spoofed = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                spoofed.add("203.0.113." + (i + 1));
            }

            List<Integer> statuses = sendAll(outsideClient, "GET", "/api/channels/0021", spoofed);

            assertThat(statuses).contains(429);
        }

        @Test
        @DisplayName("should pass the login block 429 from Spring through unchanged")
        void passesSpringLoginBlock() throws Exception {
            String body = body(edgeClient, "POST", "198.51.100.11", "/api/auth/login");

            assertThat(body).contains("\"code\":\"MEMBER_LOGIN_BLOCKED\"");
        }
    }
}
