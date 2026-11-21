package com.livestream.broadcast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// 방송 시작·종료(PRD channel C2). MediaMTX 훅과 1분 대조가 같은 처리 함수(start·end)를 쓰고, 둘 다 멱등이다
@Service
public class BroadcastService {

    private final BroadcastMapper broadcastMapper;
    private final MediaMtxClient mediaMtxClient;

    BroadcastService(BroadcastMapper broadcastMapper, MediaMtxClient mediaMtxClient) {
        this.broadcastMapper = broadcastMapper;
        this.mediaMtxClient = mediaMtxClient;
    }

    // runOnAvailable. live/{채널ID}가 아니면(720/7 등) 아무것도 하지 않는다
    public void onAvailable(String path) {
        LivePath.channelIdOf(path).ifPresent(this::start);
    }

    // runOnUnavailable
    public void onUnavailable(String path) {
        LivePath.channelIdOf(path).ifPresent(this::end);
    }

    // 알림을 놓쳐도 1분 안에 맞춘다. MediaMTX API 오류는 그대로 던진다 —
    // 응답이 없는 것을 "송출이 하나도 없다"로 읽으면 진행 중 방송을 모두 끝내 버린다
    public void reconcile() {
        Set<Long> available = new HashSet<>();
        for (String path : mediaMtxClient.findAvailablePaths()) {
            LivePath.channelIdOf(path).ifPresent(available::add);
        }
        List<Long> live = broadcastMapper.findLiveChannelIds();
        for (long channelId : available) {
            if (!live.contains(channelId)) {
                start(channelId);
            }
        }
        for (long channelId : live) {
            if (!available.contains(channelId)) {
                end(channelId);
            }
        }
    }

    // 먼저 조회하지 않고 넣는다. 이미 진행 중이면 uk_broadcast_live_channel 위반 — 서버 2대가 동시에 와도 하나만 남는다
    private void start(long channelId) {
        try {
            broadcastMapper.insertLive(channelId);
        } catch (DuplicateKeyException alreadyLive) {
            // 같은 알림이 두 번 왔거나, 훅과 대조가 겹쳤다
        }
    }

    private void end(long channelId) {
        broadcastMapper.endLive(channelId);
    }
}
