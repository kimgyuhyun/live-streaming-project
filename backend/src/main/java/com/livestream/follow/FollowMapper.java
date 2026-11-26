package com.livestream.follow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// SQL은 resources/mapper/follow/FollowMapper.xml
@Mapper
public interface FollowMapper {

    // 없으면 넣고 1, 이미 있으면 0
    int insertIfAbsent(@Param("memberId") long memberId, @Param("channelId") long channelId);

    // 지운 행 수(1 또는 0)
    int delete(@Param("memberId") long memberId, @Param("channelId") long channelId);

    boolean exists(@Param("memberId") long memberId, @Param("channelId") long channelId);
}
