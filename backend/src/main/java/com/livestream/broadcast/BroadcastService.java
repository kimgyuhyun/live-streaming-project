package com.livestream.broadcast;

import org.springframework.stereotype.Service;

@Service
public class BroadcastService {

    private final BroadcastMapper broadcastMapper;
    private final MediaMtxClient mediaMtxClient;

    BroadcastService(BroadcastMapper broadcastMapper, MediaMtxClient mediaMtxClient) {
        this.broadcastMapper = broadcastMapper;
        this.mediaMtxClient = mediaMtxClient;
    }

    public void onAvailable(String path) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void onUnavailable(String path) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void reconcile() {
        throw new UnsupportedOperationException("not implemented");
    }
}
