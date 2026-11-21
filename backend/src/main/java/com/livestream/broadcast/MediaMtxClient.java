package com.livestream.broadcast;

import com.livestream.channel.PublisherDisconnector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class MediaMtxClient implements PublisherDisconnector {

    private final RestClient restClient;

    MediaMtxClient(RestClient.Builder builder, @Value("${app.mediamtx.api-url}") String apiUrl) {
        this.restClient = builder.baseUrl(apiUrl).build();
    }

    @Override
    public void disconnect(long channelId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
