package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChannelServiceTest {

    private static final long CHANNEL_ID = 5L;
    private static final long OWNER_ID = 1L;
    private static final long OTHER_MEMBER_ID = 2L;

    private ChannelMapper channelMapper;
    private ChannelService channelService;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        channelService = new ChannelService(channelMapper);
    }

    private Channel savedChannel(String description) {
        Channel channel = new Channel(OWNER_ID, "방송채널", description);
        channel.setId(CHANNEL_ID);
        return channel;
    }

    @Nested
    @DisplayName("ChannelService.find")
    class Find {

        @Test
        @DisplayName("should return name, description and offline status when the channel exists")
        void returnsChannel() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel("안녕하세요")));

            ChannelResponse response = channelService.find(CHANNEL_ID);

            assertThat(response).isEqualTo(new ChannelResponse(CHANNEL_ID, "방송채널", "안녕하세요", false, "방송채널의 방송", false));
        }

        @Test
        @DisplayName("should return live true when the channel has a live broadcast")
        void returnsLive() {
            Channel channel = savedChannel("안녕하세요");
            channel.setLive(true);
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(channel));

            assertThat(channelService.find(CHANNEL_ID).live()).isTrue();
        }

        @Test
        @DisplayName("should return an empty description when the stored description is null")
        void returnsEmptyDescriptionForNull() {
            // Oracle은 빈 문자열을 NULL로 저장한다
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel(null)));

            assertThat(channelService.find(CHANNEL_ID).description()).isEmpty();
        }

        @Test
        @DisplayName("should return the stored title when the channel has a title")
        void returnsStoredTitle() {
            Channel channel = savedChannel("소개");
            channel.setTitle("오늘은 노래");
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(channel));

            assertThat(channelService.find(CHANNEL_ID).title()).isEqualTo("오늘은 노래");
        }

        @Test
        @DisplayName("should return the channel name followed by 의 방송 as the title when no title has been set")
        void returnsDefaultTitle() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel("소개")));

            assertThat(channelService.find(CHANNEL_ID).title()).isEqualTo("방송채널의 방송");
        }

        @Test
        @DisplayName("should report the channel as demo when it is marked as demo")
        void returnsDemo() {
            Channel channel = savedChannel("소개");
            channel.setDemo(true);
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(channel));

            assertThat(channelService.find(CHANNEL_ID).demo()).isTrue();
        }

        @Test
        @DisplayName("should report the channel as not demo when it is not marked")
        void returnsNotDemo() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel("소개")));

            assertThat(channelService.find(CHANNEL_ID).demo()).isFalse();
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND when the channel does not exist")
        void rejectsMissingChannel() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> channelService.find(CHANNEL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("ChannelService.findMine")
    class FindMine {

        @Test
        @DisplayName("should return the channel of the member when the member owns one")
        void returnsOwnChannel() {
            given(channelMapper.findByMemberId(OWNER_ID)).willReturn(Optional.of(savedChannel("소개")));

            assertThat(channelService.findMine(OWNER_ID))
                    .isEqualTo(new ChannelResponse(CHANNEL_ID, "방송채널", "소개", false, "방송채널의 방송", false));
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND when the member has no channel")
        void rejectsMemberWithoutChannel() {
            given(channelMapper.findByMemberId(OWNER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> channelService.findMine(OWNER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("ChannelService.update")
    class Update {

        @Test
        @DisplayName("should save the new name and description and return them when the owner updates")
        void savesOwnersChange() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel("소개")));

            ChannelResponse response = channelService.update(CHANNEL_ID, OWNER_ID,
                    new UpdateChannelRequest("새이름", "새 소개"));

            verify(channelMapper).update(CHANNEL_ID, "새이름", "새 소개");
            assertThat(response).isEqualTo(new ChannelResponse(CHANNEL_ID, "새이름", "새 소개", false, "새이름의 방송", false));
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_OWNER without saving when another member updates the channel")
        void rejectsNonOwner() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel("소개")));

            assertThatThrownBy(() -> channelService.update(CHANNEL_ID, OTHER_MEMBER_ID,
                    new UpdateChannelRequest("새이름", "새 소개")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_OWNER);
            verify(channelMapper, never()).update(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND when the channel does not exist")
        void rejectsMissingChannel() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> channelService.update(CHANNEL_ID, OWNER_ID,
                    new UpdateChannelRequest("새이름", "새 소개")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("ChannelService.updateTitle")
    class UpdateTitle {

        @Test
        @DisplayName("should save the title and return it when the streamer sets a title")
        void savesTitle() {
            given(channelMapper.findByMemberId(OWNER_ID)).willReturn(Optional.of(savedChannel("소개")));

            ChannelResponse response = channelService.updateTitle(OWNER_ID, new UpdateTitleRequest("오늘은 노래"));

            verify(channelMapper).updateTitle(CHANNEL_ID, "오늘은 노래");
            assertThat(response.title()).isEqualTo("오늘은 노래");
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND without saving when the member has no channel")
        void rejectsMemberWithoutChannel() {
            given(channelMapper.findByMemberId(OWNER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> channelService.updateTitle(OWNER_ID, new UpdateTitleRequest("오늘은 노래")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
            verify(channelMapper, never()).updateTitle(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("ChannelService.markDemo")
    class MarkDemo {

        @Test
        @DisplayName("should return the channel as demo when an operator marks an existing channel")
        void returnsDemoChannel() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel("소개")));

            assertThat(channelService.markDemo(CHANNEL_ID).demo()).isTrue();
        }

        @Test
        @DisplayName("should save the demo mark to the channel when an operator marks it")
        void savesDemoMark() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(savedChannel("소개")));

            channelService.markDemo(CHANNEL_ID);

            verify(channelMapper).markDemo(CHANNEL_ID);
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND when the channel does not exist")
        void rejectsMissingChannel() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> channelService.markDemo(CHANNEL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
            verify(channelMapper, never()).markDemo(anyLong());
        }
    }
}
