package com.livestream.channel;

import org.springframework.stereotype.Service;

@Service
public class ChannelService {

    public ChannelService(ChannelMapper channelMapper) {
    }

    public ChannelResponse find(long channelId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public ChannelResponse findMine(long memberId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public ChannelResponse update(long channelId, long memberId, UpdateChannelRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
