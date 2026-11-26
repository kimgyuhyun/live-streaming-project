package com.livestream.channel;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

// 스트리머 전용(403)·로그인 필요(401)는 StreamKeyIntegrationTest에서 본다
@WebMvcTest(StreamKeyController.class)
@AutoConfigureMockMvc(addFilters = false)
class StreamKeyControllerTest {

    private static final long MEMBER_ID = 1L;
    private static final String KEY = "abcdEFGH_ijkl-MNOPqrstUVWXyz0123456789ABCDE";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StreamKeyService streamKeyService;

    // 스튜디오 경로에 끼는 약관 인터셉터(#35)가 묻는다. 이 테스트는 컨트롤러만 보므로 늘 "동의함"
    @MockitoBean
    private StudioTermsService studioTermsService;

    @BeforeEach
    void agreedToStudioTerms() {
        given(studioTermsService.hasAgreed(anyLong())).willReturn(true);
    }

    // 필터를 껐으므로 SecurityContextHolder에 직접 올린다. 테스트가 끝나면 spring-security-test가 비운다
    @BeforeEach
    void logInAsStreamer() {
        TestSecurityContextHolder.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginMember(MEMBER_ID, Role.STREAMER), null, List.of()));
    }

    @Nested
    @DisplayName("POST /api/studio/stream-key")
    class Issue {

        @Test
        @DisplayName("should return 200 with the full key, prefix, OBS server and OBS stream key value when service issues a key")
        void returnsIssuedKey() throws Exception {
            given(streamKeyService.issue(MEMBER_ID)).willReturn(
                    new IssuedStreamKeyResponse(KEY, "abcd", "rtmp://localhost/live", "5?key=" + KEY));

            mockMvc.perform(post("/api/studio/stream-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.streamKey").value(KEY))
                    .andExpect(jsonPath("$.keyPrefix").value("abcd"))
                    .andExpect(jsonPath("$.obsServer").value("rtmp://localhost/live"))
                    .andExpect(jsonPath("$.obsStreamKey").value("5?key=" + KEY));
        }
    }

    @Nested
    @DisplayName("GET /api/studio/stream-key")
    class Find {

        @Test
        @DisplayName("should return 200 with the key prefix and the OBS server")
        void returnsPrefix() throws Exception {
            given(streamKeyService.find(MEMBER_ID))
                    .willReturn(new StreamKeyResponse("abcd", "rtmp://localhost/live"));

            mockMvc.perform(get("/api/studio/stream-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keyPrefix").value("abcd"))
                    .andExpect(jsonPath("$.obsServer").value("rtmp://localhost/live"));
        }
    }
}
