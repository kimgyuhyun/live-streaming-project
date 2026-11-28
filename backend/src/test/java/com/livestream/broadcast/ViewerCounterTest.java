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
import java.util.ArrayList;
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
        viewerCounter = new ViewerCounter(mediaMtxClient, broadcastMapper, viewerSnapshot, 5);
        given(viewerSnapshot.tryLock()).willReturn(true);
    }

    private static HlsSession session(String path) {
        return new HlsSession(path, "172.18.0.9:40000");
    }

    // 같은 IP에서 연 세션 count개. 탭마다 포트가 다르다
    private static List<HlsSession> sessionsFrom(String ip, String path, int count) {
        List<HlsSession> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sessions.add(new HlsSession(path, ip + ":" + (40000 + i)));
        }
        return sessions;
    }

    @SafeVarargs
    private static List<HlsSession> concat(List<HlsSession>... lists) {
        List<HlsSession> all = new ArrayList<>();
        for (List<HlsSession> list : lists) {
            all.addAll(list);
        }
        return all;
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

        // ── IP당 세션 한도(#40) ───────────────────────────────

        @Test
        @DisplayName("should write A=7 when channel A has 8 sessions from one IP and 2 sessions from two other IPs")
        void limitsSessionsOfOneIp() {
            given(mediaMtxClient.findHlsSessions()).willReturn(concat(
                    sessionsFrom("203.0.113.7", "live/1", 8),
                    sessionsFrom("203.0.113.8", "live/1", 1),
                    sessionsFrom("198.51.100.4", "live/1", 1)));
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(11, 1)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 7L));
        }

        @Test
        @DisplayName("should write A=5 and B=5 when one IP has 5 sessions on A and 5 on B")
        void limitsPerPath() {
            given(mediaMtxClient.findHlsSessions()).willReturn(concat(
                    sessionsFrom("203.0.113.7", "live/1", 5),
                    sessionsFrom("203.0.113.7", "live/2", 5)));
            given(broadcastMapper.findLiveBroadcasts())
                    .willReturn(List.of(new LiveBroadcast(11, 1), new LiveBroadcast(12, 2)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 5L, 12L, 5L));
        }

        @Test
        @DisplayName("should write A=5 and B=3 when one IP has 8 sessions on A and 3 on B")
        void overLimitOnOnePathDoesNotCutAnother() {
            given(mediaMtxClient.findHlsSessions()).willReturn(concat(
                    sessionsFrom("203.0.113.7", "live/1", 8),
                    sessionsFrom("203.0.113.7", "live/2", 3)));
            given(broadcastMapper.findLiveBroadcasts())
                    .willReturn(List.of(new LiveBroadcast(11, 1), new LiveBroadcast(12, 2)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 5L, 12L, 3L));
        }

        @Test
        @DisplayName("should write A=5 when one IP has exactly 5 sessions on channel A")
        void countsAllAtTheLimit() {
            given(mediaMtxClient.findHlsSessions()).willReturn(sessionsFrom("203.0.113.7", "live/1", 5));
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(11, 1)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 5L));
        }

        @Test
        @DisplayName("should write A=5 when one IP has 6 sessions on channel A")
        void cutsOneOverTheLimit() {
            given(mediaMtxClient.findHlsSessions()).willReturn(sessionsFrom("203.0.113.7", "live/1", 6));
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(11, 1)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 5L));
        }

        @Test
        @DisplayName("should count sessions from the same IP on different ports as one IP")
        void ignoresPort() {
            // 같은 주소에 포트만 다른 세션 6개 + 같은 포트 번호를 쓰는 다른 주소 1개
            given(mediaMtxClient.findHlsSessions()).willReturn(concat(
                    sessionsFrom("203.0.113.7", "live/1", 6),
                    List.of(new HlsSession("live/1", "203.0.113.70:40000"))));
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(11, 1)));

            viewerCounter.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 6L));
        }

        @Test
        @DisplayName("should write A=2 when the limit is set to 2 and one IP has 3 sessions on channel A")
        void usesConfiguredLimit() {
            ViewerCounter limitedToTwo = new ViewerCounter(mediaMtxClient, broadcastMapper, viewerSnapshot, 2);
            given(mediaMtxClient.findHlsSessions()).willReturn(sessionsFrom("203.0.113.7", "live/1", 3));
            given(broadcastMapper.findLiveBroadcasts()).willReturn(List.of(new LiveBroadcast(11, 1)));

            limitedToTwo.aggregate();

            assertThat(written()).isEqualTo(Map.of(11L, 2L));
        }
    }
}
