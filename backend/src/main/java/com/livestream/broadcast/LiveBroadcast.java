package com.livestream.broadcast;

// 진행 중 방송 한 줄(#39). 시청자 수 판은 방송 id로 센다
public record LiveBroadcast(long id, long channelId) {
}
