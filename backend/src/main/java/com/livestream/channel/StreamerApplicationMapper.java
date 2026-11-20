package com.livestream.channel;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

// SQL은 resources/mapper/channel/StreamerApplicationMapper.xml
@Mapper
public interface StreamerApplicationMapper {

    // 생성된 id를 application에 채운다
    void insert(StreamerApplication application);

    boolean existsPendingByMemberId(long memberId);

    // id가 가장 큰(가장 최근) 신청의 상태
    Optional<ApplicationStatus> findLatestStatusByMemberId(long memberId);
}
