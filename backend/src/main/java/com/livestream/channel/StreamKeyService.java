package com.livestream.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StreamKeyService {

    public StreamKeyService(ChannelMapper channelMapper, @Value("${app.obs-server}") String obsServer) {
    }

    public IssuedStreamKeyResponse issue(long memberId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public StreamKeyResponse find(long memberId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
