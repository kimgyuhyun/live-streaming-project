package com.livestream.follow;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.LoginMember;
import com.livestream.member.Role;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

// 로그인 필요(401)는 보안 필터가 정한다. FollowIntegrationTest에서 본다
@WebMvcTest(FollowController.class)
@AutoConfigureMockMvc(addFilters = false)
class FollowControllerTest {

    private static final long CHANNEL_ID = 5L;
    private static final long MEMBER_ID = 1L;
    private static final String PATH = "/api/channels/{channelId}/follow";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FollowService followService;

    // 필터를 껐으므로 SecurityContextHolder에 직접 올린다. 테스트가 끝나면 spring-security-test가 비운다
    @BeforeEach
    void logIn() {
        TestSecurityContextHolder.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginMember(MEMBER_ID, Role.MEMBER), null, List.of()));
    }

    @Nested
    @DisplayName("FollowController")
    class Endpoints {

        @Test
        @DisplayName("should return 204 when a member follows a channel")
        void follows() throws Exception {
            mockMvc.perform(put(PATH, CHANNEL_ID))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 204 when a member unfollows a channel")
        void unfollows() throws Exception {
            mockMvc.perform(delete(PATH, CHANNEL_ID))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 200 with following true when the member follows the channel")
        void returnsStatus() throws Exception {
            given(followService.isFollowing(MEMBER_ID, CHANNEL_ID)).willReturn(true);

            mockMvc.perform(get(PATH, CHANNEL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.following").value(true));
        }

        @Test
        @DisplayName("should return 400 with code CANNOT_FOLLOW_OWN_CHANNEL when service rejects the own channel")
        void rejectsOwnChannel() throws Exception {
            willThrow(new BusinessException(ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL))
                    .given(followService).follow(MEMBER_ID, CHANNEL_ID);

            mockMvc.perform(put(PATH, CHANNEL_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CANNOT_FOLLOW_OWN_CHANNEL"));
        }

        @Test
        @DisplayName("should return 404 with code CHANNEL_NOT_FOUND when service reports the channel does not exist")
        void rejectsMissingChannel() throws Exception {
            willThrow(new BusinessException(ErrorCode.CHANNEL_NOT_FOUND))
                    .given(followService).follow(MEMBER_ID, CHANNEL_ID);

            mockMvc.perform(put(PATH, CHANNEL_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        }
    }
}
