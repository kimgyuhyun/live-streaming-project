package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class BroadcastServiceTest {

    private BroadcastMapper broadcastMapper;
    private MediaMtxClient mediaMtxClient;
    private BroadcastService service;

    @BeforeEach
    void setUp() {
        broadcastMapper = mock(BroadcastMapper.class);
        mediaMtxClient = mock(MediaMtxClient.class);
        service = new BroadcastService(broadcastMapper, mediaMtxClient);
    }

    @Nested
    @DisplayName("BroadcastService.onAvailable")
    class OnAvailable {

        @Test
        @DisplayName("should start a live broadcast for the channel when live and the channel id becomes available")
        void startsBroadcast() {
            service.onAvailable("live/7");

            verify(broadcastMapper).insertLive(7L);
        }

        // 두 번째 INSERT는 uk_broadcast_live_channel 위반이다. 이미 진행 중이라는 뜻이라 그대로 둔다
        @Test
        @DisplayName("should keep the existing live broadcast when the channel is already live")
        void keepsExistingBroadcast() {
            willThrow(new DuplicateKeyException("uk_broadcast_live_channel")).given(broadcastMapper).insertLive(7L);

            assertThatCode(() -> service.onAvailable("live/7")).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"720/7", "live/abc", "live/007", "live/7/x", ""})
        @DisplayName("should change nothing when the path is not live followed by a channel id")
        void ignoresOtherPaths(String path) {
            service.onAvailable(path);

            verifyNoInteractions(broadcastMapper);
        }
    }

    @Nested
    @DisplayName("BroadcastService.onUnavailable")
    class OnUnavailable {

        @Test
        @DisplayName("should end the live broadcast of the channel when live and the channel id becomes unavailable")
        void endsBroadcast() {
            service.onUnavailable("live/7");

            verify(broadcastMapper).endLive(7L);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"720/7", "live/abc", "live/007", "live/7/x", ""})
        @DisplayName("should change nothing when the path is not live followed by a channel id")
        void ignoresOtherPaths(String path) {
            service.onUnavailable(path);

            verifyNoInteractions(broadcastMapper);
        }
    }

    @Nested
    @DisplayName("BroadcastService.reconcile")
    class Reconcile {

        @Test
        @DisplayName("should start broadcasts for channels available in MediaMTX but not live in the database")
        void startsMissingBroadcasts() {
            given(mediaMtxClient.findAvailablePaths()).willReturn(List.of("live/7"));
            given(broadcastMapper.findLiveChannelIds()).willReturn(List.of());

            service.reconcile();

            verify(broadcastMapper).insertLive(7L);
        }

        @Test
        @DisplayName("should end broadcasts live in the database but not available in MediaMTX")
        void endsStaleBroadcasts() {
            given(mediaMtxClient.findAvailablePaths()).willReturn(List.of());
            given(broadcastMapper.findLiveChannelIds()).willReturn(List.of(8L));

            service.reconcile();

            verify(broadcastMapper).endLive(8L);
        }

        @Test
        @DisplayName("should leave channels that are both available and live untouched")
        void leavesMatchingChannels() {
            given(mediaMtxClient.findAvailablePaths()).willReturn(List.of("live/7"));
            given(broadcastMapper.findLiveChannelIds()).willReturn(List.of(7L));

            service.reconcile();

            verify(broadcastMapper, never()).insertLive(anyLong());
            verify(broadcastMapper, never()).endLive(anyLong());
        }

        @Test
        @DisplayName("should ignore available paths that are not live followed by a channel id")
        void ignoresOtherPaths() {
            given(mediaMtxClient.findAvailablePaths()).willReturn(List.of("720/7", "live/007", "live/abc"));
            given(broadcastMapper.findLiveChannelIds()).willReturn(List.of());

            service.reconcile();

            verify(broadcastMapper, never()).insertLive(anyLong());
        }

        // 응답하지 않는 MediaMTX를 "송출이 하나도 없다"로 읽으면 진행 중 방송을 모두 끝내 버린다
        @Test
        @DisplayName("should change nothing when the MediaMTX API fails")
        void changesNothingWhenApiFails() {
            given(mediaMtxClient.findAvailablePaths())
                    .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
            given(broadcastMapper.findLiveChannelIds()).willReturn(List.of(8L));

            catchThrowable(() -> service.reconcile());

            verify(broadcastMapper, never()).endLive(anyLong());
        }
    }
}
