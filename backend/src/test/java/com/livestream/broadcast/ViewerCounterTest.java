package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.livestream.broadcast.MediaMtxClient.HlsSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

// MediaMTX·DB·Redis 없이 센 결과(판에 쓰는 값)만 본다. 잠금·판의 Redis 동작은 ViewerCountIntegrationTest가 본다
class ViewerCounterTest {

    private MediaMtxClient mediaMtxClient;
    private BroadcastMapper broadcastMapper;
    private ViewerSnapshot viewerSnapshot;
    private ViewerCounter viewerCounter;

    @BeforeEach
    void setUp() {
        mediaMtxClient = mock(MediaMtxClient.class);
        broadcastMapper = mock(BroadcastMapper.class);
        viewerSnapshot = mock(ViewerSnapshot.class);
        viewerCounter = new ViewerCounter(mediaMtxClient, broadcastMapper, viewerSnapshot);
        given(viewerSnapshot.tryLock()).willReturn(true);
    }

    private static HlsSession session(String path) {
        return new HlsSession(path, "172.18.0.9:40000");
    }

    // 판에 쓴 값(방송ID → 수)
    @SuppressWarnings("unchecked")
    private Map<Long, Long> written() {
        ArgumentCaptor<Map<Long, Long>> captor = ArgumentCaptor.forClass(Map.class);
        verify(viewerSnapshot).write(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("ViewerCounter.aggregate")
    class Aggregate {

        @Test
        @DisplayName("should write A=3 and B=1 without C when live channels A and B have 3 and 1 sessions and channel C without a live broadcast has 2")
        void countsLiveChannelsOnly() {
            // 채널 A=1, B=2는 방송 중(방송 11·12), C=3은 방송 중이 아니다
            given(mediaMtxClient.findHlsSessions()).willReturn(List.of(
                    session("live/1"), session("live/1"), session("live/1"),
                    session("live/2"),
                    session("live/3"), session("live/3")));
            given(broadcastMapper.findLiveBroadcasts())
                    .willReturn(List.of(new LiveBroadcast(11, 1), new LiveBroadcast(12, 2)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 3L, 12L, 1L));
        }

        @Test
        @DisplayName("should write 0 for live channel D when D has no session")
        void writesZeroForLiveChannelWithoutSessions() {
            given(mediaMtxClient.findHlsSessions()).willReturn(List.of());
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(14, 4)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(14L, 0L));
        }

        @Test
        @DisplayName("should leave out sessions whose path is not live and a channel id")
        void leavesOutOtherPaths() {
            // live/007은 live/7과 다른 경로다(LivePath). 720/1은 화질 경로 모양
            given(mediaMtxClient.findHlsSessions()).willReturn(List.of(
                    session("live/1"), session("live/001"), session("720/1"), session("live/1/x")));
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(11, 1)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 1L));
        }

        @Test
        @DisplayName("should not read the session list when another server holds the round lock")
        void skipsWhenLockIsHeld() {
            given(viewerSnapshot.tryLock()).willReturn(false);

            viewerCounter.aggregate();

            verifyNoInteractions(mediaMtxClient);
        }

        @Test
        @DisplayName("should not throw and not write a snapshot when the MediaMTX call fails")
        void skipsRoundWhenMediaMtxFails() {
            given(mediaMtxClient.findHlsSessions()).willThrow(new ResourceAccessException("connection refused"));
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(11, 1)));

            assertThatCode(() -> viewerCounter.aggregate()).doesNotThrowAnyException();
            verify(viewerSnapshot, never()).write(any());
        }
    }
}
