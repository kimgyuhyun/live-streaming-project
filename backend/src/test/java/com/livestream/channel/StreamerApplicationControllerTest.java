package com.livestream.channel;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.LoginMember;
import com.livestream.member.Role;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 로그인 필요·출처 검증은 StreamerApplicationIntegrationTest에서 본다
@WebMvcTest(StreamerApplicationController.class)
@AutoConfigureMockMvc(addFilters = false)
class StreamerApplicationControllerTest {

    private static final long MEMBER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StreamerApplicationService streamerApplicationService;

    // 필터를 껐으므로 SecurityContextHolder에 직접 올린다. 테스트가 끝나면 spring-security-test가 비운다
    private static void logInAs(Role role) {
        TestSecurityContextHolder.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginMember(MEMBER_ID, role), null, List.of()));
    }

    @Nested
    @DisplayName("POST /api/streamer-applications")
    class Apply {

        @Test
        @DisplayName("should return 201 with status PENDING when service accepts application")
        void returnsCreatedWithPending() throws Exception {
            given(streamerApplicationService.apply(MEMBER_ID, Role.MEMBER))
                    .willReturn(new StreamerApplicationResponse(ApplicationStatus.PENDING));
            logInAs(Role.MEMBER);

            mockMvc.perform(post("/api/streamer-applications"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("should return 409 with code CHANNEL_APPLICATION_PENDING and its message when service rejects duplicate")
        void returnsConflictWhenPending() throws Exception {
            given(streamerApplicationService.apply(MEMBER_ID, Role.MEMBER))
                    .willThrow(new BusinessException(ErrorCode.CHANNEL_APPLICATION_PENDING));
            logInAs(Role.MEMBER);

            mockMvc.perform(post("/api/streamer-applications"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHANNEL_APPLICATION_PENDING"))
                    .andExpect(jsonPath("$.message").value("이미 대기 중인 스트리머 신청이 있습니다"));
        }

        @Test
        @DisplayName("should return 409 with code CHANNEL_APPLICATION_NOT_MEMBER and its message when service rejects non-member")
        void returnsConflictWhenNotMember() throws Exception {
            given(streamerApplicationService.apply(MEMBER_ID, Role.STREAMER))
                    .willThrow(new BusinessException(ErrorCode.CHANNEL_APPLICATION_NOT_MEMBER));
            logInAs(Role.STREAMER);

            mockMvc.perform(post("/api/streamer-applications"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHANNEL_APPLICATION_NOT_MEMBER"))
                    .andExpect(jsonPath("$.message").value("회원만 스트리머 신청을 할 수 있습니다"));
        }
    }

    @Nested
    @DisplayName("GET /api/streamer-applications/me")
    class Mine {

        @Test
        @DisplayName("should return 200 with the application status of the logged-in member")
        void returnsMyStatus() throws Exception {
            given(streamerApplicationService.findMine(MEMBER_ID))
                    .willReturn(new StreamerApplicationResponse(ApplicationStatus.REJECTED));
            logInAs(Role.MEMBER);

            mockMvc.perform(get("/api/streamer-applications/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
        }
    }
}
