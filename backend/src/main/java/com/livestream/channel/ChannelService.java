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

    // 내 채널의 방송 제목을 바꾼다. 방송 중이면 다음 조회부터 새 제목이 보인다
    public ChannelResponse updateTitle(long memberId, UpdateTitleRequest request) {
        Channel channel = channelMapper.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_NOT_FOUND));
        channelMapper.updateTitle(channel.getId(), request.title());
        channel.setTitle(request.title());
        return toResponse(channel);
    }

    // 운영자가 채널에 데모 표시를 붙인다(PRD channel C3). 이미 데모 채널이어도 같은 응답
    public ChannelResponse markDemo(long channelId) {
        Channel channel = findChannel(channelId);
        channelMapper.markDemo(channelId);
        channel.setDemo(true);
        return toResponse(channel);
    }

    private Channel findChannel(long channelId) {
        return channelMapper.findById(channelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_NOT_FOUND));
    }

    // live는 진행 중 방송이 있는지(ChannelMapper가 broadcast에서 함께 읽는다). 빈 소개는 Oracle에서 NULL로 읽힌다.
    // 제목을 정한 적 없으면 "{채널 이름}의 방송"(확정 사양 4절). demo는 운영자가 붙인 데모 표시(#14)
    private static ChannelResponse toResponse(Channel channel) {
        return new ChannelResponse(channel.getId(), channel.getName(),
                Objects.requireNonNullElse(channel.getDescription(), ""), channel.isLive(),
                Objects.requireNonNullElse(channel.getTitle(), channel.getName() + "의 방송"), channel.isDemo());
    }
}
