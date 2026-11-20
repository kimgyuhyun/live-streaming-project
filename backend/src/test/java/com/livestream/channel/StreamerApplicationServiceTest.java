package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.Role;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StreamerApplicationServiceTest {

    private static final long MEMBER_ID = 1L;

    private StreamerApplicationMapper mapper;
    private StreamerApplicationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(StreamerApplicationMapper.class);
        service = new StreamerApplicationService(mapper);
    }

    private StreamerApplication insertedApplication() {
        ArgumentCaptor<StreamerApplication> captor = ArgumentCaptor.forClass(StreamerApplication.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("should save a pending application and return PENDING when member has never applied")
        void savesPendingApplication() {
            given(mapper.existsPendingByMemberId(MEMBER_ID)).willReturn(false);

            StreamerApplicationResponse response = service.apply(MEMBER_ID, Role.MEMBER);

            assertThat(response.status()).isEqualTo(ApplicationStatus.PENDING);
            assertThat(insertedApplication())
                    .extracting(StreamerApplication::getMemberId, StreamerApplication::getStatus)
                    .containsExactly(MEMBER_ID, ApplicationStatus.PENDING);
        }

        @Test
        @DisplayName("should save a new pending application when member has no pending application after a rejection")
        void savesNewPendingApplicationAfterRejection() {
            // 거절된 신청은 대기가 아니므로 대기 확인이 "없음"을 낸다. 실제 DB로는 통합 테스트가 본다
            given(mapper.existsPendingByMemberId(MEMBER_ID)).willReturn(false);

            service.apply(MEMBER_ID, Role.MEMBER);

            assertThat(insertedApplication().getStatus()).isEqualTo(ApplicationStatus.PENDING);
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_PENDING when member has a pending application")
        void rejectsWhenPendingApplicationExists() {
            given(mapper.existsPendingByMemberId(MEMBER_ID)).willReturn(true);

            assertThatThrownBy(() -> service.apply(MEMBER_ID, Role.MEMBER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_PENDING);
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_NOT_MEMBER when role is STREAMER")
        void rejectsStreamer() {
            assertThatThrownBy(() -> service.apply(MEMBER_ID, Role.STREAMER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_NOT_MEMBER);
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_NOT_MEMBER when role is OPERATOR")
        void rejectsOperator() {
            assertThatThrownBy(() -> service.apply(MEMBER_ID, Role.OPERATOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_NOT_MEMBER);
        }
    }

    @Nested
    @DisplayName("findMine")
    class FindMine {

        @Test
        @DisplayName("should return the status of the latest application when member has applied")
        void returnsLatestStatus() {
            given(mapper.findLatestStatusByMemberId(MEMBER_ID)).willReturn(Optional.of(ApplicationStatus.REJECTED));

            assertThat(service.findMine(MEMBER_ID).status()).isEqualTo(ApplicationStatus.REJECTED);
        }

        @Test
        @DisplayName("should return NONE when member has never applied")
        void returnsNoneWhenNeverApplied() {
            given(mapper.findLatestStatusByMemberId(MEMBER_ID)).willReturn(Optional.empty());

            assertThat(service.findMine(MEMBER_ID).status()).isEqualTo(ApplicationStatus.NONE);
        }
    }
}
