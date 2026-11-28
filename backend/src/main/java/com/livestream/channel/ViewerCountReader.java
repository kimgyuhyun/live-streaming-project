package com.livestream.channel;

// 진행 중 방송의 지금 시청자 수를 읽는 쪽(#39). 구현은 broadcast.ViewerSnapshot.
// broadcast가 channel을 부르므로 channel은 broadcast를 모르게 여기에 둔다(PublisherDisconnector와 같은 이유)
public interface ViewerCountReader {

    // 지금 판에서 그 방송의 시청자 수. 판이 없거나(만료 포함) 판에 그 방송이 없으면 0
    long viewerCount(long broadcastId);
}
