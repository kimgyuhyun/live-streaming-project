package com.livestream.channel;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// SQL은 resources/mapper/channel/StreamerApplicationMapper.xml
@Mapper
public interface StreamerApplicationMapper {

    // 생성된 id를 application에 채운다
    void insert(StreamerApplication application);

    boolean existsPendingByMemberId(long memberId);

    // id가 가장 큰(가장 최근) 신청의 상태
    Optional<ApplicationStatus> findLatestStatusByMemberId(long memberId);

    Optional<StreamerApplication> findById(long id);

    // 대기일 때만 status로 바꾼다. 바뀐 행 수(0 또는 1)
    int updateStatusIfPending(@Param("id") long id, @Param("status") ApplicationStatus status);

    // 대기 신청과 신청 회원의 닉네임·이메일. 신청 시각이 오래된 순(같으면 id 순)
    List<PendingApplicationResponse> findPending();
}
