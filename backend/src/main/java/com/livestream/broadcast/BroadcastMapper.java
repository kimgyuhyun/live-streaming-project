package com.livestream.broadcast;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

// SQL은 resources/mapper/broadcast/BroadcastMapper.xml
@Mapper
public interface BroadcastMapper {

    // 진행 중 방송을 하나 만든다. 이미 진행 중이면 uk_broadcast_live_channel 위반(DuplicateKeyException)
    void insertLive(long channelId);

    // 그 채널의 진행 중 방송에 종료 시각을 찍는다. 없으면 0행
    void endLive(long channelId);

    List<Long> findLiveChannelIds();
}
