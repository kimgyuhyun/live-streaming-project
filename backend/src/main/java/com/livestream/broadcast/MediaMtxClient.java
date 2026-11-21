package com.livestream.broadcast;

import com.livestream.channel.PublisherDisconnector;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// MediaMTX API(1.21, 내부 :9997). 호스트 포트로 열지 않는다 — 연결 목록의 query에 키가 그대로 있다(PRD channel)
@Component
class MediaMtxClient implements PublisherDisconnector {

    private final RestClient restClient;

    MediaMtxClient(RestClient.Builder builder, @Value("${app.mediamtx.api-url}") String apiUrl) {
        this.restClient = builder.baseUrl(apiUrl).build();
    }

    // live/{채널ID}로 송출 중인 RTMP 연결을 끊는다(스파이크 1절 E·F). 없으면 아무것도 하지 않는다.
    // API 오류는 RestClientException 그대로 던진다 — 재발급 쪽(StreamKeyService)이 받는다
    @Override
    public void disconnect(long channelId) {
        String path = "live/" + channelId;
        RtmpConnections connections = restClient.get().uri("/v3/rtmpconns/list")
                .retrieve().body(RtmpConnections.class);
        for (RtmpConnection connection : connections.items()) {
            if ("publish".equals(connection.state()) && path.equals(connection.path())) {
                restClient.post().uri("/v3/rtmpconns/kick/{id}", connection.id()).retrieve().toBodilessEntity();
            }
        }
    }

    // 필요한 필드만 읽는다. query(키가 들어 있다)는 받지 않는다
    record RtmpConnections(List<RtmpConnection> items) {
    }

    record RtmpConnection(String id, String state, String path) {
    }
}
