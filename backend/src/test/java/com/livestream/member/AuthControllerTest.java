package com.livestream.member;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.common.error.RetryLaterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 세션 쿠키·세션 ID 교체·출처 검증은 MemberLoginIntegrationTest에서 진짜 필터와 Redis로 본다.
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    private static final String EMAIL = "viewer@example.com";
    private static final String PASSWORD = "abcd1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("should return 200 with email, nickname and role when service accepts login")
        void returnsMember() throws Exception {
            given(memberService.login(any(LoginRequest.class), any()))
                    .willReturn(new MemberResponse(1L, EMAIL, "시청자1", Role.MEMBER));

            login(EMAIL, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(EMAIL))
                    .andExpect(jsonPath("$.nickname").value("시청자1"))
                    .andExpect(jsonPath("$.role").value("MEMBER"));
        }

        @Test
        @DisplayName("should return 400 when password is 65 characters")
        void rejectsPasswordOf65() throws Exception {
            login(EMAIL, "a".repeat(32) + "1".repeat(33))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when password contains non-ascii characters")
        void rejectsNonAsciiPassword() throws Exception {
            login(EMAIL, "비밀번호abcd1234")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 401 with code MEMBER_LOGIN_FAILED and its message when service rejects login")
        void returnsUnauthorizedWhenRejected() throws Exception {
            given(memberService.login(any(LoginRequest.class), any()))
                    .willThrow(new BusinessException(ErrorCode.MEMBER_LOGIN_FAILED));

            login(EMAIL, "wrong1234")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MEMBER_LOGIN_FAILED"))
                    .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 맞지 않습니다"));
        }

        // 실제 IP는 Tomcat이 X-Forwarded-For에서 remoteAddr로 옮겨 둔다(forward-headers-strategy: native)
        @Test
        @DisplayName("should pass the client ip to the service")
        void passesClientIp() throws Exception {
            given(memberService.login(any(LoginRequest.class), any()))
                    .willReturn(new MemberResponse(1L, EMAIL, "시청자1", Role.MEMBER));

            mockMvc.perform(post("/api/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("198.51.100.7");
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, PASSWORD))));

            verify(memberService).login(any(LoginRequest.class), eq("198.51.100.7"));
        }

        @Test
        @DisplayName("should return 429 with Retry-After header when attempts are blocked")
        void returnsTooManyRequestsWithRetryAfter() throws Exception {
            given(memberService.login(any(LoginRequest.class), any()))
                    .willThrow(new RetryLaterException(ErrorCode.MEMBER_LOGIN_BLOCKED, 840));

            login(EMAIL, PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "840"));
        }

        @Test
        @DisplayName("should round remaining time up to whole minutes in the message")
        void roundsMinutesUp() throws Exception {
            given(memberService.login(any(LoginRequest.class), any()))
                    .willThrow(new RetryLaterException(ErrorCode.MEMBER_LOGIN_BLOCKED, 61));

            login(EMAIL, PASSWORD)
                    .andExpect(jsonPath("$.message").value("로그인 시도가 너무 많습니다. 2분 후 다시 시도해 주세요"));
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when email is blank")
        void rejectsBlankEmail() throws Exception {
            login(" ", PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }
}
