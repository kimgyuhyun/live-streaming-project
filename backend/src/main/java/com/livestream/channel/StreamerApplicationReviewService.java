package com.livestream.channel;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.Member;
import com.livestream.member.MemberMapper;
import com.livestream.member.Role;
import java.util.List;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 운영자의 대기 목록·승인·거절
@Service
public class StreamerApplicationReviewService {

    private final StreamerApplicationMapper applicationMapper;
    private final MemberMapper memberMapper;
    private final ChannelMapper channelMapper;
    private final TransactionTemplate transactionTemplate;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public StreamerApplicationReviewService(StreamerApplicationMapper applicationMapper, MemberMapper memberMapper,
                                            ChannelMapper channelMapper, TransactionTemplate transactionTemplate,
                                            FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.applicationMapper = applicationMapper;
        this.memberMapper = memberMapper;
        this.channelMapper = channelMapper;
        this.transactionTemplate = transactionTemplate;
        this.sessionRepository = sessionRepository;
    }

    public List<PendingApplicationResponse> findPending() {
        return applicationMapper.findPending();
    }

    // 승인 → 역할 STREAMER → 채널 생성을 한 트랜잭션으로(PRD channel "승인 처리"). 중간에 실패하면 전부 되돌린다.
    // 세션은 커밋 뒤에 지운다. 커밋 전에 지우면 그 사이 다시 로그인한 세션에 MEMBER 역할이 남는다(PRD "승인 후 로그인 상태")
    public StreamerApplicationResponse approve(long applicationId) {
        long memberId = transactionTemplate.execute(status -> {
            StreamerApplication application = findApplication(applicationId);
            decide(applicationId, ApplicationStatus.APPROVED);
            Member member = memberMapper.findById(application.getMemberId()).orElseThrow();
            memberMapper.updateRole(member.getId(), Role.STREAMER);
            channelMapper.insert(new Channel(member.getId(), member.getNickname(), ""));
            return member.getId();
        });
        sessionRepository.findByPrincipalName(String.valueOf(memberId)).keySet()
                .forEach(sessionRepository::deleteById);
        return new StreamerApplicationResponse(ApplicationStatus.APPROVED);
    }

    // 바꾸는 문장이 조건부 UPDATE 하나라 트랜잭션을 열지 않는다
    public StreamerApplicationResponse reject(long applicationId) {
        findApplication(applicationId);
        decide(applicationId, ApplicationStatus.REJECTED);
        return new StreamerApplicationResponse(ApplicationStatus.REJECTED);
    }

    private StreamerApplication findApplication(long applicationId) {
        return applicationMapper.findById(applicationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_APPLICATION_NOT_FOUND));
    }

    // 대기일 때만 바뀐다. 0행이면 이미 처리된 신청이다(동시 승인에서 진 쪽 포함)
    private void decide(long applicationId, ApplicationStatus status) {
        if (applicationMapper.updateStatusIfPending(applicationId, status) == 0) {
            throw new BusinessException(ErrorCode.CHANNEL_APPLICATION_ALREADY_DECIDED);
        }
    }
}
