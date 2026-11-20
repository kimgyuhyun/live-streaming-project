package com.livestream.channel;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ChannelService {

    private final ChannelMapper channelMapper;

    public ChannelService(ChannelMapper channelMapper) {
        this.channelMapper = channelMapper;
    }

    public ChannelResponse find(long channelId) {
        return toResponse(findChannel(channelId));
    }

    // 스튜디오가 내 채널 id와 지금 값을 읽는다
    public ChannelResponse findMine(long memberId) {
        return toResponse(channelMapper.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_NOT_FOUND)));
    }

    // 주인만 고친다. 채널마다 주인이 달라 경로 규칙(SecurityConfig)으로는 막을 수 없다
    public ChannelResponse update(long channelId, long memberId, UpdateChannelRequest request) {
        Channel channel = findChannel(channelId);
        if (channel.getMemberId() != memberId) {
            throw new BusinessException(ErrorCode.CHANNEL_NOT_OWNER);
        }
        channelMapper.update(channelId, request.name(), request.description());
        channel.setName(request.name());
        channel.setDescription(request.description());
        return toResponse(channel);
    }

    private Channel findChannel(long channelId) {
        return channelMapper.findById(channelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_NOT_FOUND));
    }

    // 방송 상태는 #11에서 채운다. 그 전까지 늘 오프라인. 빈 소개는 Oracle에서 NULL로 읽힌다
    private static ChannelResponse toResponse(Channel channel) {
        return new ChannelResponse(channel.getId(), channel.getName(),
                Objects.requireNonNullElse(channel.getDescription(), ""), false);
    }
}
