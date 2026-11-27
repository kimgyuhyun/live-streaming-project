package com.livestream.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

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

        // 같은 회원의 팔로우가 동시에 두 번 오면 늦은 쪽의 MERGE가 기본 키에 걸린다
        @Test
        @DisplayName("should not change the follower count when saving the follow hits a duplicate key")
        void keepsCountOnDuplicateKey() {
            channelExists();
            given(followMapper.insertIfAbsent(MEMBER_ID, CHANNEL_ID)).willThrow(new DuplicateKeyException("pk_follow"));

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

    @Nested
    @DisplayName("FollowService.following")
    class Following {

        private static final int PAGE = FollowService.FOLLOWING_PAGE_SIZE;

        private List<FollowingChannel> rows(int count) {
            return IntStream.rangeClosed(1, count)
                    .mapToObj(i -> new FollowingChannel(i, "채널" + i, false, "제목" + i))
                    .toList();
        }

        @Test
        @DisplayName("should return every channel from the mapper with hasMore false when the mapper finds 3 channels")
        void returnsAllWhenFew() {
            given(followMapper.findFollowing(MEMBER_ID, 0, PAGE + 1)).willReturn(rows(3));

            FollowingResponse response = followService.following(MEMBER_ID, 0);

            assertThat(response).isEqualTo(new FollowingResponse(rows(3), false));
        }

        @Test
        @DisplayName("should ask the mapper for 21 channels from the given offset")
        void asksForOneMore() {
            given(followMapper.findFollowing(anyLong(), anyInt(), anyInt())).willReturn(List.of());

            followService.following(MEMBER_ID, 20);

            verify(followMapper).findFollowing(MEMBER_ID, 20, 21);
        }

        @Test
        @DisplayName("should return 20 channels with hasMore true when the mapper finds 21 channels")
        void cutsAtTwentyWhenMore() {
            given(followMapper.findFollowing(MEMBER_ID, 0, PAGE + 1)).willReturn(rows(21));

            FollowingResponse response = followService.following(MEMBER_ID, 0);

            assertThat(response).isEqualTo(new FollowingResponse(rows(20), true));
        }

        @Test
        @DisplayName("should return hasMore false when the mapper finds exactly 20 channels")
        void noMoreAtExactlyTwenty() {
            given(followMapper.findFollowing(MEMBER_ID, 0, PAGE + 1)).willReturn(rows(20));

            FollowingResponse response = followService.following(MEMBER_ID, 0);

            assertThat(response).isEqualTo(new FollowingResponse(rows(20), false));
        }

        @Test
        @DisplayName("should return the channel name followed by 의 방송 as the title when the channel has never set a title")
        void defaultsTitle() {
            given(followMapper.findFollowing(MEMBER_ID, 0, PAGE + 1))
                    .willReturn(List.of(new FollowingChannel(7, "하늘", true, null)));

            FollowingResponse response = followService.following(MEMBER_ID, 0);

            assertThat(response.items()).containsExactly(new FollowingChannel(7, "하늘", true, "하늘의 방송"));
        }

        @Test
        @DisplayName("should throw INVALID_INPUT when the offset is negative")
        void rejectsNegativeOffset() {
            assertThatThrownBy(() -> followService.following(MEMBER_ID, -1))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }
}
