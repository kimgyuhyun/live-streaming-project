package com.livestream.follow;

import com.livestream.channel.ChannelMapper;
import org.springframework.stereotype.Service;

@Service
public class FollowService {

    private final FollowMapper followMapper;
    private final ChannelMapper channelMapper;

    public FollowService(FollowMapper followMapper, ChannelMapper channelMapper) {
        this.followMapper = followMapper;
        this.channelMapper = channelMapper;
    }

    public void follow(long memberId, long channelId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void unfollow(long memberId, long channelId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean isFollowing(long memberId, long channelId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
