package com.livestream.channel;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.Role;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class StreamerApplicationService {

    private final StreamerApplicationMapper mapper;

    public StreamerApplicationService(StreamerApplicationMapper mapper) {
        this.mapper = mapper;
    }

    // 역할이 MEMBER가 아니면 CHANNEL_APPLICATION_NOT_MEMBER, 대기 신청이 있으면 CHANNEL_APPLICATION_PENDING.
    // 동시에 들어와 확인을 함께 통과한 경우는 uk_streamer_application_pending(V2)이 막는다.
    // 이 테이블의 유니크 제약은 그것 하나뿐이라 중복 키 위반은 모두 "대기 신청 있음"이다
    public StreamerApplicationResponse apply(long memberId, Role role) {
        if (role != Role.MEMBER) {
            throw new BusinessException(ErrorCode.CHANNEL_APPLICATION_NOT_MEMBER);
        }
        if (mapper.existsPendingByMemberId(memberId)) {
            throw new BusinessException(ErrorCode.CHANNEL_APPLICATION_PENDING);
        }
        try {
            mapper.insert(new StreamerApplication(memberId, ApplicationStatus.PENDING));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CHANNEL_APPLICATION_PENDING);
        }
        return new StreamerApplicationResponse(ApplicationStatus.PENDING);
    }

    // 신청이 없으면 NONE
    public StreamerApplicationResponse findMine(long memberId) {
        return new StreamerApplicationResponse(
                mapper.findLatestStatusByMemberId(memberId).orElse(ApplicationStatus.NONE));
    }
}
