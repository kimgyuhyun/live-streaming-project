package com.livestream.channel;

import com.livestream.member.Role;
import org.springframework.stereotype.Service;

@Service
public class StreamerApplicationService {

    private final StreamerApplicationMapper mapper;

    public StreamerApplicationService(StreamerApplicationMapper mapper) {
        this.mapper = mapper;
    }

    // 역할이 MEMBER가 아니면 CHANNEL_APPLICATION_NOT_MEMBER, 대기 신청이 있으면 CHANNEL_APPLICATION_PENDING
    public StreamerApplicationResponse apply(long memberId, Role role) {
        throw new UnsupportedOperationException("not implemented");
    }

    // 신청이 없으면 NONE
    public StreamerApplicationResponse findMine(long memberId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
