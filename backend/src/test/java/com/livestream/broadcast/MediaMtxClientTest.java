package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

// 실제 MediaMTX를 부르지 않는다. MockRestServiceServer가 RestClient의 요청을 받아 정해 둔 응답을 준다
class MediaMtxClientTest {

    private static final String API = "http://mediamtx.test:9997";
    private static final String LIST = API + "/v3/rtmpconns/list";
    private static final String INTERNAL_PASSWORD = "internal-Pw_123";
    // Basic 인증 값은 "사용자:비밀번호"의 Base64
    private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
            .encodeToString(("spring:" + INTERNAL_PASSWORD).getBytes(StandardCharsets.UTF_8));

    private MockRestServiceServer server;
    private MediaMtxClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MediaMtxClient(builder, API, INTERNAL_PASSWORD);
    }

    // 1.21 응답 모양(스파이크 1절 E). 실제 응답에는 query에 키가 그대로 있다
    private static String connections(String... items) {
        return "{\"pageCount\":1,\"itemCount\":" + items.length + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String connection(String id, String state, String path) {
        return "{\"id\":\"" + id + "\",\"created\":\"2026-11-21T00:00:00Z\",\"remoteAddr\":\"172.18.0.1:50000\","
                + "\"state\":\"" + state + "\",\"path\":\"" + path + "\",\"query\":\"key=secret\","
                + "\"bytesReceived\":1,\"bytesSent\":0}";
    }

    // 1.21 /v3/paths/list 모양(issue-11.md 1.21 확인 사항). available이 1.21에서 ready의 새 이름이다
    private static String path(String name, boolean available) {
        return "{\"name\":\"" + name + "\",\"confName\":\"all_others\",\"ready\":" + available + ","
                + "\"available\":" + available + ",\"online\":" + available + ","
                + "\"source\":{\"type\":\"rtmpConn\",\"id\":\"x\"},\"tracks\":[\"H264\"],\"readers\":[]}";
    }

    // 1.21 /v3/hlssessions/list 모양(로컬 도커 실측, #39). 페이지 번호는 0부터, 세션이 없으면 pageCount 0
    private static String sessionPage(int pageCount, String... items) {
        return "{\"itemCount\":" + items.length + ",\"pageCount\":" + pageCount + ",\"items\":["
                + String.join(",", items) + "]}";
    }

    private static String session(String path, String remoteAddr) {
        return "{\"id\":\"s\",\"created\":\"2026-11-27T22:05:39Z\",\"remoteAddr\":\"" + remoteAddr + "\","
                + "\"path\":\"" + path + "\",\"query\":\"\",\"user\":\"\",\"expiresAt\":null,"
                + "\"userAgent\":\"curl/8.14.1\",\"isCDN\":false,\"outboundBytes\":413}";
    }

    @Nested
    @DisplayName("MediaMtxClient.findHlsSessions")
    class FindHlsSessions {

        private static final String SESSIONS = API + "/v3/hlssessions/list";

        @Test
        @DisplayName("should return the path and remote address of each session")
        void returnsPathAndRemoteAddress() {
            server.expect(requestTo(startsWith(SESSIONS))).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(sessionPage(1, session("live/7", "172.18.0.9:41000"),
                            session("live/8", "172.18.0.9:41002")), MediaType.APPLICATION_JSON));

            assertThat(client.findHlsSessions()).containsExactly(
                    new MediaMtxClient.HlsSession("live/7", "172.18.0.9:41000"),
                    new MediaMtxClient.HlsSession("live/8", "172.18.0.9:41002"));
        }

        @Test
        @DisplayName("should read every page when the session list has 3 pages")
        void readsEveryPage() {
            server.expect(requestTo(startsWith(SESSIONS))).andExpect(queryParam("page", "0"))
                    .andRespond(withSuccess(sessionPage(3, session("live/7", "a:1")), MediaType.APPLICATION_JSON));
            server.expect(requestTo(startsWith(SESSIONS))).andExpect(queryParam("page", "1"))
                    .andRespond(withSuccess(sessionPage(3, session("live/7", "a:2")), MediaType.APPLICATION_JSON));
            server.expect(requestTo(startsWith(SESSIONS))).andExpect(queryParam("page", "2"))
                    .andRespond(withSuccess(sessionPage(3, session("live/8", "a:3")), MediaType.APPLICATION_JSON));

            assertThat(client.findHlsSessions()).extracting(MediaMtxClient.HlsSession::remoteAddr)
                    .containsExactly("a:1", "a:2", "a:3");
        }

        @Test
        @DisplayName("should return no sessions after a single request when the list reports 0 pages")
        void returnsNothingForZeroPages() {
            // 두 번째 요청이 오면 MockRestServiceServer가 예상하지 못한 요청으로 실패시킨다
            server.expect(requestTo(startsWith(SESSIONS))).andExpect(queryParam("page", "0"))
                    .andRespond(withSuccess(sessionPage(0), MediaType.APPLICATION_JSON));

            assertThat(client.findHlsSessions()).isEmpty();
        }

        @Test
        @DisplayName("should throw when the MediaMTX API responds with an error")
        void throwsOnApiError() {
            server.expect(requestTo(startsWith(SESSIONS))).andRespond(withServerError());

            assertThatThrownBy(() -> client.findHlsSessions()).isInstanceOf(HttpServerErrorException.class);
        }
    }

    @Nested
    @DisplayName("MediaMtxClient.findAvailablePaths")
    class FindAvailablePaths {

        private static final String PATHS = API + "/v3/paths/list";

        @Test
        @DisplayName("should return the names of the available paths")
        void returnsAvailablePaths() {
            server.expect(requestTo(PATHS)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(connections(path("live/7", true), path("live/8", true)),
                            MediaType.APPLICATION_JSON));

            assertThat(client.findAvailablePaths()).containsExactly("live/7", "live/8");
        }

        @Test
        @DisplayName("should send the internal user and password as basic auth when listing paths")
        void sendsBasicAuthWhenListingPaths() {
            server.expect(requestTo(PATHS)).andExpect(header(HttpHeaders.AUTHORIZATION, BASIC_AUTH))
                    .andRespond(withSuccess(connections(), MediaType.APPLICATION_JSON));

            client.findAvailablePaths();

            server.verify();
        }

        @Test
        @DisplayName("should leave out paths that are not available")
        void leavesOutUnavailablePaths() {
            server.expect(requestTo(PATHS))
                    .andRespond(withSuccess(connections(path("live/7", false), path("live/8", true)),
                            MediaType.APPLICATION_JSON));

            assertThat(client.findAvailablePaths()).containsExactly("live/8");
        }

        @Test
        @DisplayName("should throw when the MediaMTX API responds with an error")
        void throwsOnApiError() {
            server.expect(requestTo(PATHS)).andRespond(withServerError());

            assertThatThrownBy(() -> client.findAvailablePaths()).isInstanceOf(HttpServerErrorException.class);
        }
    }

    @Nested
    @DisplayName("MediaMtxClient.disconnect")
    class Disconnect {

        @Test
        @DisplayName("should kick the connection publishing to live and the channel id")
        void kicksPublisher() {
            server.expect(requestTo(LIST)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(connections(
                            connection("other", "publish", "live/70"),
                            connection("target", "publish", "live/7")), MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/v3/rtmpconns/kick/target")).andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess());

            client.disconnect(7);

            server.verify();
        }

        @Test
        @DisplayName("should send the internal user and password as basic auth when listing and kicking connections")
        void sendsBasicAuthWhenListingAndKicking() {
            server.expect(requestTo(LIST)).andExpect(header(HttpHeaders.AUTHORIZATION, BASIC_AUTH))
                    .andRespond(withSuccess(connections(connection("target", "publish", "live/7")),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/v3/rtmpconns/kick/target"))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC_AUTH))
                    .andRespond(withSuccess());

            client.disconnect(7);

            server.verify();
        }

        @Test
        @DisplayName("should kick nothing when no connection publishes to that path")
        void kicksNothingWithoutPublisher() {
            // 같은 경로의 시청 연결(read)과 다른 경로의 송출은 끊지 않는다. 기대하지 않은 요청이 오면 실패한다
            server.expect(requestTo(LIST)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(connections(
                            connection("reader", "read", "live/7"),
                            connection("other", "publish", "live/8")), MediaType.APPLICATION_JSON));

            client.disconnect(7);

            server.verify();
        }

        @Test
        @DisplayName("should throw when the MediaMTX API responds with an error")
        void throwsOnApiError() {
            server.expect(requestTo(LIST)).andRespond(withServerError());

            assertThatThrownBy(() -> client.disconnect(7)).isInstanceOf(HttpServerErrorException.class);
        }
    }
}
