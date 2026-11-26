package com.livestream.follow;

import com.livestream.channel.Channel;
import com.livestream.channel.ChannelMapper;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 팔로우·취소·팔로우 여부(#37, PRD viewer 작은 결정). 세 가지 모두 없는 채널은 CHANNEL_NOT_FOUND,
// 자기 채널은 CANNOT_FOLLOW_OWN_CHANNEL(화면은 팔로우 여부의 이 400으로 내 채널 버튼을 숨긴다)
@Service
public class FollowService {

    private final FollowMapper followMapper;
    private final ChannelMapper channelMapper;

    public FollowService(FollowMapper followMapper, ChannelMapper channelMapper) {
        this.followMapper = followMapper;
        this.channelMapper = channelMapper;
    }

    // 새로 들어갔을 때만 팔로워 수 +1. 숫자는 DB가 더한다(읽고 더해 쓰면 동시 팔로우에서 잃는다 — defect-repro 기록은 issue-37.md)
    @Transactional
    public void follow(long memberId, long channelId) {
        checkFollowable(memberId, channelId);
        if (insertIfAbsent(memberId, channelId)) {
            channelMapper.increaseFollowerCount(channelId);
        }
    }

    // 실제로 지웠을 때만 팔로워 수 -1. 팔로우하지 않았으면 아무것도 바뀌지 않는다(0 아래로 내려가지 않는다)
    @Transactional
    public void unfollow(long memberId, long channelId) {
        checkFollowable(memberId, channelId);
        if (followMapper.delete(memberId, channelId) == 1) {
            channelMapper.decreaseFollowerCount(channelId);
        }
    }

    public boolean isFollowing(long memberId, long channelId) {
        checkFollowable(memberId, channelId);
        return followMapper.exists(memberId, channelId);
    }

    private void checkFollowable(long memberId, long channelId) {
        Channel channel = channelMapper.findById(channelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getMemberId() == memberId) {
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL);
        }
    }

    // 같은 회원의 팔로우가 동시에 두 번 오면 늦은 쪽의 MERGE가 기본 키에 걸린다. 이미 팔로우한 것과 같다
    private boolean insertIfAbsent(long memberId, long channelId) {
        try {
            return followMapper.insertIfAbsent(memberId, channelId) == 1;
        } catch (DuplicateKeyException alreadyFollowing) {
            return false;
        }
    }
}
