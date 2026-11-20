package com.livestream.channel;

import com.livestream.member.MemberMapper;
import java.util.List;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 운영자의 대기 목록·승인·거절
@Service
public class StreamerApplicationReviewService {

    public StreamerApplicationReviewService(StreamerApplicationMapper applicationMapper, MemberMapper memberMapper,
                                            ChannelMapper channelMapper, TransactionTemplate transactionTemplate,
                                            FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
    }

    public List<PendingApplicationResponse> findPending() {
        throw new UnsupportedOperationException("not implemented");
    }

    public StreamerApplicationResponse approve(long applicationId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public StreamerApplicationResponse reject(long applicationId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
