package com.livestream.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.livestream.channel.Channel;
import com.livestream.channel.ChannelMapper;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FollowServiceTest {

    private static final long MEMBER_ID = 1L;
    private static final long OWNER_ID = 9L;
    private static final long CHANNEL_ID = 5L;

    private FollowMapper followMapper;
    private ChannelMapper channelMapper;
    private FollowService followService;

    @BeforeEach
    void setUp() {
        followMapper = mock(FollowMapper.class);
        channelMapper = mock(ChannelMapper.class);
        followService = new FollowService(followMapper, channelMapper);
    }

    // OWNER_ID의 채널. MEMBER_ID에게는 남의 채널이다
    private void channelExists() {
        Channel channel = new Channel(OWNER_ID, "방송채널", "");
        channel.setId(CHANNEL_ID);
        given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
    }

    // MEMBER_ID 자신의 채널
    private void ownChannelExists() {
        Channel channel = new Channel(MEMBER_ID, "내채널", "");
        channel.setId(CHANNEL_ID);
        given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.of(channel));
    }

    @Nested
    @DisplayName("FollowService.follow")
    class Follow {

        @Test
        @DisplayName("should save the follow and add one to the follower count when the member has not followed the channel")
        void savesAndCounts() {
            channelExists();
            given(followMapper.insertIfAbsent(MEMBER_ID, CHANNEL_ID)).willReturn(1);

            followService.follow(MEMBER_ID, CHANNEL_ID);

            InOrder order = inOrder(followMapper, channelMapper);
            order.verify(followMapper).insertIfAbsent(MEMBER_ID, CHANNEL_ID);
            order.verify(channelMapper).increaseFollowerCount(CHANNEL_ID);
        }

        @Test
        @DisplayName("should not change the follower count when the member already follows the channel")
        void keepsCountWhenAlreadyFollowing() {
            channelExists();
            given(followMapper.insertIfAbsent(MEMBER_ID, CHANNEL_ID)).willReturn(0);

            followService.follow(MEMBER_ID, CHANNEL_ID);

            verify(channelMapper, never()).increaseFollowerCount(anyLong());
        }

        @Test
        @DisplayName("should throw CANNOT_FOLLOW_OWN_CHANNEL without saving when the member owns the channel")
        void rejectsOwnChannel() {
            ownChannelExists();

            assertThatThrownBy(() -> followService.follow(MEMBER_ID, CHANNEL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL);
            verify(followMapper, never()).insertIfAbsent(anyLong(), anyLong());
        }

        @Test
        @DisplayName("should throw CHANNEL_NOT_FOUND without saving when the channel does not exist")
        void rejectsMissingChannel() {
            given(channelMapper.findById(CHANNEL_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> followService.follow(MEMBER_ID, CHANNEL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND);
            verify(followMapper, never()).insertIfAbsent(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("FollowService.unfollow")
    class Unfollow {

        @Test
        @DisplayName("should delete the follow and subtract one from the follower count when the member follows the channel")
        void deletesAndCounts() {
            channelExists();
            given(followMapper.delete(MEMBER_ID, CHANNEL_ID)).willReturn(1);

            followService.unfollow(MEMBER_ID, CHANNEL_ID);

            InOrder order = inOrder(followMapper, channelMapper);
            order.verify(followMapper).delete(MEMBER_ID, CHANNEL_ID);
            order.verify(channelMapper).decreaseFollowerCount(CHANNEL_ID);
        }

        @Test
        @DisplayName("should not change the follower count when the member does not follow the channel")
        void keepsCountWhenNotFollowing() {
            channelExists();
            given(followMapper.delete(MEMBER_ID, CHANNEL_ID)).willReturn(0);

            followService.unfollow(MEMBER_ID, CHANNEL_ID);

            verify(channelMapper, never()).decreaseFollowerCount(anyLong());
        }

        @Test
        @DisplayName("should throw CANNOT_FOLLOW_OWN_CHANNEL without deleting when the member owns the channel")
        void rejectsOwnChannel() {
            ownChannelExists();

            assertThatThrownBy(() -> followService.unfollow(MEMBER_ID, CHANNEL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL);
            verify(followMapper, never()).delete(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("FollowService.isFollowing")
    class IsFollowing {

        @Test
        @DisplayName("should return true when the member follows the channel")
        void returnsTrue() {
            channelExists();
            given(followMapper.exists(MEMBER_ID, CHANNEL_ID)).willReturn(true);

            assertThat(followService.isFollowing(MEMBER_ID, CHANNEL_ID)).isTrue();
        }

        @Test
        @DisplayName("should return false when the member does not follow the channel")
        void returnsFalse() {
            channelExists();
            given(followMapper.exists(MEMBER_ID, CHANNEL_ID)).willReturn(false);

            assertThat(followService.isFollowing(MEMBER_ID, CHANNEL_ID)).isFalse();
        }

        @Test
        @DisplayName("should throw CANNOT_FOLLOW_OWN_CHANNEL when the member asks about their own channel")
        void rejectsOwnChannel() {
            ownChannelExists();

            assertThatThrownBy(() -> followService.isFollowing(MEMBER_ID, CHANNEL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL);
        }
    }
}
