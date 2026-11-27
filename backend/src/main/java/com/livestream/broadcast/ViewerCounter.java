package com.livestream.broadcast;

import org.springframework.stereotype.Component;

// 동시 시청자 수 한 회차 집계(#39, PRD viewer V1)
@Component
class ViewerCounter {

    private final MediaMtxClient mediaMtxClient;
    private final BroadcastMapper broadcastMapper;
    private final ViewerSnapshot viewerSnapshot;

    ViewerCounter(MediaMtxClient mediaMtxClient, BroadcastMapper broadcastMapper, ViewerSnapshot viewerSnapshot) {
        this.mediaMtxClient = mediaMtxClient;
        this.broadcastMapper = broadcastMapper;
        this.viewerSnapshot = viewerSnapshot;
    }

    void aggregate() {
        throw new UnsupportedOperationException("not implemented");
    }
}
