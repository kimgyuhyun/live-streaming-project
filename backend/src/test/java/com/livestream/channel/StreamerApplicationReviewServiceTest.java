package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.Member;
import com.livestream.member.MemberMapper;
import com.livestream.member.Role;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class StreamerApplicationReviewServiceTest {

    private static final long APPLICATION_ID = 10L;
    private static final long MEMBER_ID = 1L;
    private static final String NICKNAME = "신청자1";

    private StreamerApplicationMapper applicationMapper;
    private MemberMapper memberMapper;
    private ChannelMapper channelMapper;
    private PlatformTransactionManager transactionManager;
    private FindByIndexNameSessionRepository<Session> sessionRepository;
    private StreamerApplicationReviewService service;

    @BeforeEach
    void setUp() {
        applicationMapper = mock(StreamerApplicationMapper.class);
        memberMapper = mock(MemberMapper.class);
        channelMapper = mock(ChannelMapper.class);
        // 가짜 트랜잭션 매니저. 템플릿이 시작·커밋·롤백을 이 목에 알린다
        transactionManager = mock(PlatformTransactionManager.class);
        sessionRepository = mockSessionRepository();
        service = new StreamerApplicationReviewService(applicationMapper, memberMapper, channelMapper,
                new TransactionTemplate(transactionManager), sessionRepository);
    }

    @SuppressWarnings("unchecked")
    private static FindByIndexNameSessionRepository<Session> mockSessionRepository() {
        return mock(FindByIndexNameSessionRepository.class);
    }

    private void givenApplication(ApplicationStatus status) {
        StreamerApplication application = new StreamerApplication(MEMBER_ID, status);
        application.setId(APPLICATION_ID);
        given(applicationMapper.findById(APPLICATION_ID)).willReturn(Optional.of(application));
    }

    private void givenApplicant() {
        Member member = new Member("applicant@example.com", "hash", NICKNAME, Role.MEMBER);
        member.setId(MEMBER_ID);
        given(memberMapper.findById(MEMBER_ID)).willReturn(Optional.of(member));
    }

    private void givenApplicantSessions(String... sessionIds) {
        Map<String, Session> sessions = new HashMap<>();
        for (String sessionId : sessionIds) {
            sessions.put(sessionId, mock(Session.class));
        }
        given(sessionRepository.findByPrincipalName(String.valueOf(MEMBER_ID))).willReturn(sessions);
    }

    @Nested
    @DisplayName("StreamerApplicationReviewService.approve")
    class Approve {

        @Test
        @DisplayName("should mark the application approved, change the role to STREAMER and create a channel named after the nickname with an empty description when the application is pending")
        void approvesPendingApplication() {
            givenApplication(ApplicationStatus.PENDING);
            givenApplicant();
            givenApplicantSessions();
            given(applicationMapper.updateStatusIfPending(APPLICATION_ID, ApplicationStatus.APPROVED)).willReturn(1);

            StreamerApplicationResponse response = service.approve(APPLICATION_ID);

            assertThat(response.status()).isEqualTo(ApplicationStatus.APPROVED);
            verify(memberMapper).updateRole(MEMBER_ID, Role.STREAMER);
            ArgumentCaptor<Channel> channel = ArgumentCaptor.forClass(Channel.class);
            verify(channelMapper).insert(channel.capture());
            assertThat(channel.getValue())
                    .extracting(Channel::getMemberId, Channel::getName, Channel::getDescription)
                    .containsExactly(MEMBER_ID, NICKNAME, "");
        }

        @Test
        @DisplayName("should delete every session of the applicant after the approval is committed")
        void deletesSessionsAfterCommit() {
            givenApplication(ApplicationStatus.PENDING);
            givenApplicant();
            givenApplicantSessions("session-1", "session-2");
            given(applicationMapper.updateStatusIfPending(APPLICATION_ID, ApplicationStatus.APPROVED)).willReturn(1);

            service.approve(APPLICATION_ID);

            InOrder order = inOrder(transactionManager, sessionRepository);
            order.verify(transactionManager).commit(any());
            order.verify(sessionRepository).deleteById("session-1");
            order.verify(sessionRepository).deleteById("session-2");
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_NOT_FOUND when the application does not exist")
        void rejectsMissingApplication() {
            given(applicationMapper.findById(APPLICATION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.approve(APPLICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_ALREADY_DECIDED without changing the role, creating a channel or deleting sessions when the application is no longer pending")
        void rejectsDecidedApplication() {
            givenApplication(ApplicationStatus.REJECTED);
            givenApplicant();
            given(applicationMapper.updateStatusIfPending(APPLICATION_ID, ApplicationStatus.APPROVED)).willReturn(0);

            assertThatThrownBy(() -> service.approve(APPLICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_ALREADY_DECIDED);
            verify(memberMapper, never()).updateRole(anyLong(), any());
            verify(channelMapper, never()).insert(any());
            verify(sessionRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("should not delete sessions when channel creation fails")
        void keepsSessionsWhenChannelCreationFails() {
            givenApplication(ApplicationStatus.PENDING);
            givenApplicant();
            givenApplicantSessions("session-1");
            given(applicationMapper.updateStatusIfPending(APPLICATION_ID, ApplicationStatus.APPROVED)).willReturn(1);
            IllegalStateException failure = new IllegalStateException("channel insert failed");
            willThrow(failure).given(channelMapper).insert(any());

            assertThatThrownBy(() -> service.approve(APPLICATION_ID)).isSameAs(failure);
            verify(sessionRepository, never()).deleteById(anyString());
        }
    }

    @Nested
    @DisplayName("StreamerApplicationReviewService.reject")
    class Reject {

        @Test
        @DisplayName("should mark the application rejected without changing the role or creating a channel when the application is pending")
        void rejectsPendingApplication() {
            givenApplication(ApplicationStatus.PENDING);
            given(applicationMapper.updateStatusIfPending(APPLICATION_ID, ApplicationStatus.REJECTED)).willReturn(1);

            StreamerApplicationResponse response = service.reject(APPLICATION_ID);

            assertThat(response.status()).isEqualTo(ApplicationStatus.REJECTED);
            verify(applicationMapper).updateStatusIfPending(APPLICATION_ID, ApplicationStatus.REJECTED);
            verify(memberMapper, never()).updateRole(anyLong(), any());
            verify(channelMapper, never()).insert(any());
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_NOT_FOUND when the application does not exist")
        void rejectsMissingApplication() {
            given(applicationMapper.findById(APPLICATION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.reject(APPLICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_ALREADY_DECIDED when the application is no longer pending")
        void rejectsDecidedApplication() {
            givenApplication(ApplicationStatus.APPROVED);
            given(applicationMapper.updateStatusIfPending(APPLICATION_ID, ApplicationStatus.REJECTED)).willReturn(0);

            assertThatThrownBy(() -> service.reject(APPLICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_ALREADY_DECIDED);
        }
    }
}
