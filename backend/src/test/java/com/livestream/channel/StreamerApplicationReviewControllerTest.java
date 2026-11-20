package com.livestream.channel;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 운영자 권한(403)·로그인 필요(401)는 StreamerApplicationReviewIntegrationTest에서 본다
@WebMvcTest(StreamerApplicationReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class StreamerApplicationReviewControllerTest {

    private static final long APPLICATION_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StreamerApplicationReviewService reviewService;

    @Nested
    @DisplayName("GET /api/operator/streamer-applications")
    class Pending {

        @Test
        @DisplayName("should return 200 with id, nickname, email and applied time of each pending application")
        void returnsPendingApplications() throws Exception {
            given(reviewService.findPending()).willReturn(List.of(new PendingApplicationResponse(
                    APPLICATION_ID, "신청자1", "applicant@example.com", LocalDateTime.of(2026, 11, 20, 9, 30, 15))));

            mockMvc.perform(get("/api/operator/streamer-applications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(APPLICATION_ID))
                    .andExpect(jsonPath("$[0].nickname").value("신청자1"))
                    .andExpect(jsonPath("$[0].email").value("applicant@example.com"))
                    .andExpect(jsonPath("$[0].appliedAt").value("2026-11-20T09:30:15"));
        }
    }

    @Nested
    @DisplayName("POST /api/operator/streamer-applications/{id}/approve")
    class Approve {

        @Test
        @DisplayName("should return 200 with status APPROVED when service approves")
        void returnsApproved() throws Exception {
            given(reviewService.approve(APPLICATION_ID))
                    .willReturn(new StreamerApplicationResponse(ApplicationStatus.APPROVED));

            mockMvc.perform(post("/api/operator/streamer-applications/{id}/approve", APPLICATION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        @Test
        @DisplayName("should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED and its message when service reports the application is already decided")
        void returnsConflictWhenDecided() throws Exception {
            given(reviewService.approve(APPLICATION_ID))
                    .willThrow(new BusinessException(ErrorCode.CHANNEL_APPLICATION_ALREADY_DECIDED));

            mockMvc.perform(post("/api/operator/streamer-applications/{id}/approve", APPLICATION_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHANNEL_APPLICATION_ALREADY_DECIDED"))
                    .andExpect(jsonPath("$.message").value("이미 처리된 스트리머 신청입니다"));
        }

        @Test
        @DisplayName("should return 404 with code CHANNEL_APPLICATION_NOT_FOUND and its message when service reports the application does not exist")
        void returnsNotFound() throws Exception {
            given(reviewService.approve(APPLICATION_ID))
                    .willThrow(new BusinessException(ErrorCode.CHANNEL_APPLICATION_NOT_FOUND));

            mockMvc.perform(post("/api/operator/streamer-applications/{id}/approve", APPLICATION_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHANNEL_APPLICATION_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("스트리머 신청을 찾을 수 없습니다"));
        }
    }

    @Nested
    @DisplayName("POST /api/operator/streamer-applications/{id}/reject")
    class Reject {

        @Test
        @DisplayName("should return 200 with status REJECTED when service rejects")
        void returnsRejected() throws Exception {
            given(reviewService.reject(APPLICATION_ID))
                    .willReturn(new StreamerApplicationResponse(ApplicationStatus.REJECTED));

            mockMvc.perform(post("/api/operator/streamer-applications/{id}/reject", APPLICATION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
        }
    }
}
