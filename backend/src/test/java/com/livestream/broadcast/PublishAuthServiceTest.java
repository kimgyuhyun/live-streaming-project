package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.livestream.channel.StreamKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PublishAuthServiceTest {

    private static final long CHANNEL_ID = 7L;
    private static final String KEY = "Abc_def-123ghiJKLmnoPQRstuVWXyz0123456789ab";

    private StreamKeyService streamKeyService;
    private PublishAuthService service;

    @BeforeEach
    void setUp() {
        streamKeyService = mock(StreamKeyService.class);
        service = new PublishAuthService(streamKeyService);
        given(streamKeyService.matches(CHANNEL_ID, KEY)).willReturn(true);
    }

    private static MediaMtxAuthRequest publish(String path, String query) {
        return new MediaMtxAuthRequest("publish", path, query);
    }

    @Nested
    @DisplayName("PublishAuthService.canPublish")
    class CanPublish {

        @Test
        @DisplayName("should allow when action is publish, path is live and the channel id, and the query key matches")
        void allowsMatchingKey() {
            assertThat(service.canPublish(publish("live/7", "key=" + KEY))).isTrue();
        }

        @Test
        @DisplayName("should find the key among other query parameters")
        void findsKeyAmongOtherParameters() {
            assertThat(service.canPublish(publish("live/7", "foo=1&key=" + KEY + "&bar=2"))).isTrue();
        }

        // MediaMTX는 query가 없으면 빈 문자열을 보낸다. null과 값이 빈 key도 같이 본다
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"key="})
        @DisplayName("should deny when the query is empty")
        void deniesEmptyQuery(String query) {
            assertThat(service.canPublish(publish("live/7", query))).isFalse();
        }

        @Test
        @DisplayName("should deny when the channel id in the path is too long for a number")
        void deniesTooLongChannelId() {
            assertThat(service.canPublish(publish("live/99999999999999999999", "key=" + KEY))).isFalse();
        }

        // long의 최대값은 19자리(9223372036854775807)다. 18자리는 늘 long에 들어간다
        @Test
        @DisplayName("should pass an 18-digit channel id in the path to the key check when the path is live followed by the id")
        void passesEighteenDigitChannelId() {
            given(streamKeyService.matches(999_999_999_999_999_999L, KEY)).willReturn(true);

            assertThat(service.canPublish(publish("live/999999999999999999", "key=" + KEY))).isTrue();
        }

        @Test
        @DisplayName("should deny without calling the key check when the channel id in the path has 19 digits")
        void deniesNineteenDigitChannelId() {
            boolean allowed = service.canPublish(publish("live/9999999999999999999", "key=" + KEY));

            assertThat(allowed).isFalse();
            verify(streamKeyService, never()).matches(anyLong(), anyString());
        }

        // live/007은 MediaMTX에서 live/7과 다른 경로다. 받아 주면 같은 채널로 두 번째 송출이 되고,
        // 재발급 때 live/7만 끊으므로 이전 키로 하던 그 송출이 살아남는다(보안 점검)
        @ParameterizedTest
        @ValueSource(strings = {"live/07", "live/007", "live/0"})
        @DisplayName("should deny when the channel id in the path starts with zero")
        void deniesLeadingZero(String path) {
            given(streamKeyService.matches(0L, KEY)).willReturn(true);

            assertThat(service.canPublish(publish(path, "key=" + KEY))).isFalse();
        }

        @Test
        @DisplayName("should deny when the key does not match")
        void deniesWrongKey() {
            given(streamKeyService.matches(CHANNEL_ID, "wrong")).willReturn(false);

            assertThat(service.canPublish(publish("live/7", "key=wrong"))).isFalse();
        }

        @Test
        @DisplayName("should deny when the query has no key")
        void deniesQueryWithoutKey() {
            assertThat(service.canPublish(publish("live/7", "foo=" + KEY))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"live/abc", "720/7", "live/7/x", "live/", "live/-7", "7", "xlive/7"})
        @DisplayName("should deny when the path is not live followed by a number")
        void deniesOtherPaths(String path) {
            assertThat(service.canPublish(publish(path, "key=" + KEY))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"read", "playback", "api", ""})
        @DisplayName("should deny when the action is not publish")
        void deniesOtherActions(String action) {
            assertThat(service.canPublish(new MediaMtxAuthRequest(action, "live/7", "key=" + KEY))).isFalse();
        }
    }
}
