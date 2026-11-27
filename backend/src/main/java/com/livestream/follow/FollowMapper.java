package com.livestream.follow;

import java.util.List;
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

    // 내가 팔로우한 채널을 방송 중 먼저 → 팔로우 시각 최근 순으로 offset부터 limit개. title은 저장값 그대로(NULL 가능)
    List<FollowingChannel> findFollowing(@Param("memberId") long memberId,
                                         @Param("offset") int offset, @Param("limit") int limit);
}
