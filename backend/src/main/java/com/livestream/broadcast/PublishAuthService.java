package com.livestream.broadcast;

import com.livestream.channel.StreamKeyService;
import org.springframework.stereotype.Service;

@Service
public class PublishAuthService {

    private final StreamKeyService streamKeyService;

    public PublishAuthService(StreamKeyService streamKeyService) {
        this.streamKeyService = streamKeyService;
    }

    public boolean canPublish(MediaMtxAuthRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
