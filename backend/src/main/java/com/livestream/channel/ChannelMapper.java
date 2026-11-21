package com.livestream.channel;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// SQL은 resources/mapper/channel/ChannelMapper.xml
@Mapper
public interface ChannelMapper {

    // 생성된 id를 channel에 채운다. 같은 회원의 두 번째 채널은 uk_channel_member 위반
    void insert(Channel channel);

    Optional<Channel> findById(long id);

    Optional<Channel> findByMemberId(long memberId);

    void update(@Param("id") long id, @Param("name") String name, @Param("description") String description);

    void updateTitle(@Param("id") long id, @Param("title") String title);

    void updateStreamKey(@Param("id") long id, @Param("hash") String hash, @Param("prefix") String prefix);
}
