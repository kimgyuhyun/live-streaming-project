package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

// 실제 MediaMTX를 부르지 않는다. MockRestServiceServer가 RestClient의 요청을 받아 정해 둔 응답을 준다
class MediaMtxClientTest {

    private static final String API = "http://mediamtx.test:9997";
    private static final String LIST = API + "/v3/rtmpconns/list";

    private MockRestServiceServer server;
    private MediaMtxClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MediaMtxClient(builder, API);
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
