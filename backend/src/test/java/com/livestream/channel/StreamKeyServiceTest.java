package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class StreamKeyServiceTest {

    private static final long MEMBER_ID = 1L;
    private static final long CHANNEL_ID = 5L;
    private static final String OBS_SERVER = "rtmp://stream.example.test/live";

    private ChannelMapper channelMapper;
    private PublisherDisconnector publisherDisconnector;
    private StreamKeyService service;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        publisherDisconnector = mock(PublisherDisconnector.class);
        service = new StreamKeyService(channelMapper, OBS_SERVER, publisherDisconnector);
    }

    // 테스트가 기대값을 따로 계산한다. 구현과 같은 형식(SHA-256, 소문자 16진수)
    private static String sha256Hex(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private void givenChannel(String hash, String prefix) {
        Channel channel = new Channel(MEMBER_ID, "방송채널", "");
        channel.setId(CHANNEL_ID);
        channel.setStreamKeyHash(hash);
        channel.setStreamKeyPrefix(prefix);
        given(channelMapper.findByMemberId(MEMBER_ID)).willReturn(Optional.of(channel));
    }

    // 저장 요청에 넘어간 [해시, 앞 4자리]
    private String[] savedHashAndPrefix() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(channelMapper).updateStreamKey(eq(CHANNEL_ID), hash.capture(), prefix.capture());
        return new String[] {hash.getValue(), prefix.getValue()};
    }

    @Nested
    @DisplayName("StreamKeyService.issue")
    class Issue {

        @Test
        @DisplayName("should return the full key and save only its SHA-256 hash and first 4 characters when the streamer has no key")
        void issuesFirstKey() throws Exception {
            givenChannel(null, null);

            IssuedStreamKeyResponse response = service.issue(MEMBER_ID);

            assertThat(savedHashAndPrefix()).containsExactly(
                    sha256Hex(response.streamKey()), response.streamKey().substring(0, 4));
        }

        @Test
        @DisplayName("should replace the saved hash and prefix with those of the new key when the streamer already has a key")
        void reissuesKey() throws Exception {
            String oldHash = sha256Hex("old-key");
            givenChannel(oldHash, "old-");

            IssuedStreamKeyResponse response = service.issue(MEMBER_ID);

            String[] saved = savedHashAndPrefix();
            assertThat(saved).containsExactly(sha256Hex(response.streamKey()), response.streamKey().substring(0, 4));
            assertThat(saved[0]).isNotEqualTo(oldHash);
        }

        @Test
        @DisplayName("should return the OBS server and the stream key field value made of the channel id and the key")
        void returnsObsSettings() {
            givenChannel(null, null);

            IssuedStreamKeyResponse response = service.issue(MEMBER_ID);

            assertThat(response)
                    .extracting(IssuedStreamKeyResponse::obsServer, IssuedStreamKeyResponse::obsStreamKey,
                            IssuedStreamKeyResponse::keyPrefix)
                    .containsExactly(OBS_SERVER, CHANNEL_ID + "?key=" + response.streamKey(),
                            response.streamKey().substring(0, 4));
        }

        @Test
        @DisplayName("should make the key from 32 random bytes written in URL-safe characters")
        void makesUrlSafeKeyFrom32Bytes() {
            givenChannel(null, null);

            String key = service.issue(MEMBER_ID).streamKey();

            // Base64 URL(패딩 없음)로 32바이트는 43자다
            assertThat(key).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(key)).hasSize(32);
        }

        @Test
        @DisplayName("should make a different key on each issue")
        void makesDifferentKeys() {
            givenChannel(null, null);

            String first = service.issue(MEMBER_ID).streamKey();
            String second = service.issue(MEMBER_ID).streamKey();

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("should disconnect the channel's publisher after saving the new key")
        void disconnectsAfterSaving() {
            givenChannel(null, null);

            service.issue(MEMBER_ID);

            InOrder order = inOrder(channelMapper, publisherDisconnector);
            order.verify(channelMapper).updateStreamKey(eq(CHANNEL_ID), anyString(), anyString());
            order.verify(publisherDisconnector).disconnect(CHANNEL_ID);
        }

        @Test
        @DisplayName("should still return the new key when disconnecting the publisher fails")
        void returnsKeyWhenDisconnectFails() {
            givenChannel(null, null);
            willThrow(new IllegalStateException("MediaMTX down")).given(publisherDisconnector).disconnect(CHANNEL_ID);

            IssuedStreamKeyResponse response = service.issue(MEMBER_ID);

            assertThat(response.streamKey()).matches("[A-Za-z0-9_-]{43}");
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND when the member has no channel")
        void rejectsMemberWithoutChannel() {
            given(channelMapper.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.issue(MEMBER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("StreamKeyService.matches")
    class Matches {

        private static final String KEY = "abcdSecretRestOfTheKey";

        private void givenChannelById(String hash) {
            Channel channel = new Channel(MEMBER_ID, "방송채널", "");
            channel.setId(CHANNEL_ID);
            channel.setStreamKeyHash(hash);
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
        }

        @Test
        @DisplayName("should return true when the key hashes to the saved hash of the channel")
        void matchesSavedHash() throws Exception {
            givenChannelById(sha256Hex(KEY));

            assertThat(service.matches(CHANNEL_ID, KEY)).isTrue();
        }

        @Test
        @DisplayName("should return false when the channel has no key")
        void rejectsChannelWithoutKey() {
            givenChannelById(null);

            assertThat(service.matches(CHANNEL_ID, KEY)).isFalse();
        }

        @Test
        @DisplayName("should return false when the key differs from the saved one")
        void rejectsDifferentKey() throws Exception {
            givenChannelById(sha256Hex(KEY));

            assertThat(service.matches(CHANNEL_ID, KEY + "x")).isFalse();
        }

        @Test
        @DisplayName("should return false when the channel does not exist")
        void rejectsMissingChannel() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.empty());

            assertThat(service.matches(CHANNEL_ID, KEY)).isFalse();
        }
    }

    @Nested
    @DisplayName("StreamKeyService.find")
    class Find {

        @Test
        @DisplayName("should return only the first 4 characters and the OBS server when the streamer has a key")
        void returnsPrefixOnly() throws Exception {
            givenChannel(sha256Hex("abcdSecretRest"), "abcd");

            assertThat(service.find(MEMBER_ID)).isEqualTo(new StreamKeyResponse("abcd", OBS_SERVER));
        }

        @Test
        @DisplayName("should return no prefix when the streamer has no key")
        void returnsNoPrefixWithoutKey() {
            givenChannel(null, null);

            assertThat(service.find(MEMBER_ID)).isEqualTo(new StreamKeyResponse(null, OBS_SERVER));
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND when the member has no channel")
        void rejectsMemberWithoutChannel() {
            given(channelMapper.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.find(MEMBER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
        }
    }
}
