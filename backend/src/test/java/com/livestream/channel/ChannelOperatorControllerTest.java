package com.livestream.channel;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 운영자 권한(403)·로그인 필요(401)는 ChannelIntegrationTest에서 본다
@WebMvcTest(ChannelOperatorController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChannelOperatorControllerTest {

    private static final long CHANNEL_ID = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChannelService channelService;

    @Nested
    @DisplayName("PUT /api/operator/channels/{channelId}/demo")
    class MarkDemo {

        @Test
        @DisplayName("should return 200 with the channel when an operator marks it as demo")
        void returnsDemoChannel() throws Exception {
            given(channelService.markDemo(CHANNEL_ID))
                    .willReturn(new ChannelResponse(CHANNEL_ID, "데모채널1", "", true, "데모채널1의 방송", true, 0));

            mockMvc.perform(put("/api/operator/channels/{channelId}/demo", CHANNEL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.demo").value(true));
        }

        @Test
        @DisplayName("should return 404 with CHANNEL_NOT_FOUND when an operator marks a channel that does not exist")
        void returnsNotFound() throws Exception {
            given(channelService.markDemo(CHANNEL_ID)).willThrow(new BusinessException(ErrorCode.CHANNEL_NOT_FOUND));

            mockMvc.perform(put("/api/operator/channels/{channelId}/demo", CHANNEL_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        }
    }
}
