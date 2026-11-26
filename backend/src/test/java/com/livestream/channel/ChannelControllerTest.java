package com.livestream.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.LoginMember;
import com.livestream.member.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 로그인 필요·역할·출처 검증은 ChannelIntegrationTest에서 본다
@WebMvcTest(ChannelController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChannelControllerTest {

    private static final long CHANNEL_ID = 5L;
    private static final long MEMBER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChannelService channelService;

    // 스튜디오 경로에 끼는 약관 인터셉터(#35)가 묻는다. 이 테스트는 컨트롤러만 보므로 늘 "동의함"
    @MockitoBean
    private StudioTermsService studioTermsService;

    @BeforeEach
    void agreedToStudioTerms() {
        given(studioTermsService.hasAgreed(anyLong())).willReturn(true);
    }

    // 필터를 껐으므로 SecurityContextHolder에 직접 올린다. 테스트가 끝나면 spring-security-test가 비운다
    private static void logInAsStreamer() {
        TestSecurityContextHolder.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginMember(MEMBER_ID, Role.STREAMER), null, List.of()));
    }

    private ResultActions putChannel(String name, String description) throws Exception {
        logInAsStreamer();
        return mockMvc.perform(put("/api/channels/{channelId}", CHANNEL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateChannelRequest(name, description))));
    }

    // 서비스가 받은 요청을 그대로 돌려준다. 받아들인 값이 서비스까지 갔는지 응답으로 본다
    private void serviceEchoesRequest() {
        given(channelService.update(eq(CHANNEL_ID), eq(MEMBER_ID), any(UpdateChannelRequest.class)))
                .willAnswer(invocation -> {
                    UpdateChannelRequest request = invocation.getArgument(2);
                    return new ChannelResponse(CHANNEL_ID, request.name(), request.description(), false, "방송채널의 방송", false, 0);
                });
    }

    @Nested
    @DisplayName("GET /api/channels/{channelId}")
    class GetChannel {

        @Test
        @DisplayName("should return 200 with name, description and live false when service finds the channel")
        void returnsChannel() throws Exception {
            given(channelService.find(CHANNEL_ID))
                    .willReturn(new ChannelResponse(CHANNEL_ID, "방송채널", "안녕하세요", false, "방송채널의 방송", false, 0));

            mockMvc.perform(get("/api/channels/{channelId}", CHANNEL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(CHANNEL_ID))
                    .andExpect(jsonPath("$.name").value("방송채널"))
                    .andExpect(jsonPath("$.description").value("안녕하세요"))
                    .andExpect(jsonPath("$.live").value(false));
        }

        @Test
        @DisplayName("should return 404 with code CHANNEL_NOT_FOUND and its message when service reports the channel does not exist")
        void returnsNotFound() throws Exception {
            given(channelService.find(CHANNEL_ID)).willThrow(new BusinessException(ErrorCode.CHANNEL_NOT_FOUND));

            mockMvc.perform(get("/api/channels/{channelId}", CHANNEL_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("채널을 찾을 수 없습니다"));
        }
    }

    @Nested
    @DisplayName("GET /api/studio/channel")
    class MyChannel {

        @Test
        @DisplayName("should return 200 with the channel of the logged-in streamer")
        void returnsMyChannel() throws Exception {
            given(channelService.findMine(MEMBER_ID))
                    .willReturn(new ChannelResponse(CHANNEL_ID, "방송채널", "", false, "방송채널의 방송", false, 0));
            logInAsStreamer();

            mockMvc.perform(get("/api/studio/channel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(CHANNEL_ID))
                    .andExpect(jsonPath("$.name").value("방송채널"));
        }
    }

    @Nested
    @DisplayName("PUT /api/channels/{channelId}")
    class Update {

        @Test
        @DisplayName("should return 200 with the updated channel when the owner sends a valid name and description")
        void returnsUpdatedChannel() throws Exception {
            serviceEchoesRequest();

            putChannel("새이름", "새 소개")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("새이름"))
                    .andExpect(jsonPath("$.description").value("새 소개"));
        }

        @Test
        @DisplayName("should accept a name of 2 characters")
        void acceptsTwoCharacterName() throws Exception {
            serviceEchoesRequest();

            putChannel("가나", "소개").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("가나"));
        }

        @Test
        @DisplayName("should accept a name of 20 characters")
        void acceptsTwentyCharacterName() throws Exception {
            serviceEchoesRequest();
            String name = "가".repeat(20);

            putChannel(name, "소개").andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));
        }

        @Test
        @DisplayName("should accept an empty description")
        void acceptsEmptyDescription() throws Exception {
            serviceEchoesRequest();

            putChannel("새이름", "").andExpect(status().isOk()).andExpect(jsonPath("$.description").value(""));
        }

        @Test
        @DisplayName("should accept a description of 200 characters")
        void acceptsTwoHundredCharacterDescription() throws Exception {
            serviceEchoesRequest();
            String description = "가".repeat(200);

            putChannel("새이름", description)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value(description));
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the name has 1 character")
        void rejectsOneCharacterName() throws Exception {
            putChannel("가", "소개")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("채널 이름은 2~20자입니다"));
            verify(channelService, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the name has 21 characters")
        void rejectsTwentyOneCharacterName() throws Exception {
            putChannel("가".repeat(21), "소개")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("채널 이름은 2~20자입니다"));
            verify(channelService, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the description has 201 characters")
        void rejectsTwoHundredOneCharacterDescription() throws Exception {
            putChannel("새이름", "가".repeat(201))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("소개는 200자 이하입니다"));
            verify(channelService, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the name is blank")
        void rejectsBlankName() throws Exception {
            // 공백 두 칸은 길이로는 2자라 @Size만으로는 통과한다
            putChannel("  ", "소개")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("채널 이름을 입력해 주세요"));
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the description is missing")
        void rejectsMissingDescription() throws Exception {
            logInAsStreamer();

            mockMvc.perform(put("/api/channels/{channelId}", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "새이름"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("소개를 보내 주세요"));
        }

        @Test
        @DisplayName("should return 403 with code CHANNEL_NOT_OWNER and its message when service rejects a member who does not own the channel")
        void returnsForbiddenForNonOwner() throws Exception {
            given(channelService.update(eq(CHANNEL_ID), eq(MEMBER_ID), any(UpdateChannelRequest.class)))
                    .willThrow(new BusinessException(ErrorCode.CHANNEL_NOT_OWNER));

            putChannel("새이름", "새 소개")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CHANNEL_NOT_OWNER"))
                    .andExpect(jsonPath("$.message").value("내 채널만 고칠 수 있습니다"));
        }
    }

    @Nested
    @DisplayName("PUT /api/studio/title")
    class UpdateTitle {

        private ResultActions putTitle(Object body) throws Exception {
            logInAsStreamer();
            return mockMvc.perform(put("/api/studio/title")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)));
        }

        // 서비스가 받은 제목을 그대로 돌려준다. 받아들인 값이 서비스까지 갔는지 응답으로 본다
        private void serviceEchoesTitle() {
            given(channelService.updateTitle(eq(MEMBER_ID), any(UpdateTitleRequest.class)))
                    .willAnswer(invocation -> {
                        UpdateTitleRequest request = invocation.getArgument(1);
                        return new ChannelResponse(CHANNEL_ID, "방송채널", "", false, request.title(), false, 0);
                    });
        }

        @Test
        @DisplayName("should return 200 with the channel showing the new title when the streamer sends a valid title")
        void returnsChannelWithNewTitle() throws Exception {
            serviceEchoesTitle();

            putTitle(new UpdateTitleRequest("오늘은 노래"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(CHANNEL_ID))
                    .andExpect(jsonPath("$.title").value("오늘은 노래"));
        }

        @Test
        @DisplayName("should accept a title of 1 character")
        void acceptsOneCharacterTitle() throws Exception {
            serviceEchoesTitle();

            putTitle(new UpdateTitleRequest("가")).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("가"));
        }

        @Test
        @DisplayName("should accept a title of 50 characters")
        void acceptsFiftyCharacterTitle() throws Exception {
            serviceEchoesTitle();
            String title = "가".repeat(50);

            putTitle(new UpdateTitleRequest(title)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value(title));
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the title has 51 characters")
        void rejectsFiftyOneCharacterTitle() throws Exception {
            putTitle(new UpdateTitleRequest("가".repeat(51)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("방송 제목은 1~50자입니다"));
            verify(channelService, never()).updateTitle(anyLong(), any());
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the title is only spaces")
        void rejectsBlankTitle() throws Exception {
            putTitle(new UpdateTitleRequest("   "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("방송 제목을 입력해 주세요"));
            verify(channelService, never()).updateTitle(anyLong(), any());
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the title is empty")
        void rejectsEmptyTitle() throws Exception {
            putTitle(new UpdateTitleRequest(""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("방송 제목을 입력해 주세요"));
            verify(channelService, never()).updateTitle(anyLong(), any());
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT when the title is missing")
        void rejectsMissingTitle() throws Exception {
            putTitle(Map.of())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("방송 제목을 입력해 주세요"));
            verify(channelService, never()).updateTitle(anyLong(), any());
        }
    }
}
