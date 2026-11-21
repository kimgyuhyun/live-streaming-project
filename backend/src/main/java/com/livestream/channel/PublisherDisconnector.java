package com.livestream.channel;

// 재발급 뒤 그 채널의 송출 연결을 끊는 쪽. 구현은 broadcast.MediaMtxClient.
// broadcast가 키 확인으로 channel을 부르므로, channel은 broadcast를 모르게 여기에 둔다
public interface PublisherDisconnector {

    void disconnect(long channelId);
}
