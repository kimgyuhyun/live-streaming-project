package com.livestream.channel;

import org.apache.ibatis.annotations.Mapper;

// SQL은 resources/mapper/channel/ChannelMapper.xml
@Mapper
public interface ChannelMapper {

    // 생성된 id를 channel에 채운다. 같은 회원의 두 번째 채널은 uk_channel_member 위반
    void insert(Channel channel);
}
