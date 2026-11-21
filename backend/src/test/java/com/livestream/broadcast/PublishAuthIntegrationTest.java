package com.livestream.broadcast;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.TestcontainersConfiguration;
import com.livestream.channel.StreamKeyService;
import com.livestream.channel.StreamerApplicationReviewService;
import com.livestream.channel.StreamerApplicationService;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// MediaMTX가 보내는 그대로(Origin·Referer·세션 없음) 보안 필터를 모두 거쳐 키 확인 엔드포인트를 부른다.
// 스트리머는 실제 흐름(가입 → 신청 → 승인 → 키 발급)으로 만든다. DB를 테스트끼리 같이 쓰므로 매번 새로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PublishAuthIntegrationTest {

    private static final String AUTH_PATH = "/internal/mediamtx/auth";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberService memberService;

    @Autowired
    private StreamerApplicationService applicationService;

    @Autowired
    private StreamerApplicationReviewService reviewService;

    @Autowired
    private StreamKeyService streamKeyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record ChannelWithKey(long channelId, String key) {
    }

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    // 가입 → 신청 → 승인 → 키 발급
    private ChannelWithKey channelWithKey() {
        long memberId = memberService.signUp(
                new SignUpRequest("p" + unique() + "@example.com", "abcd1234", "송출" + unique())).id();
        applicationService.apply(memberId, Role.MEMBER);
        long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM streamer_application WHERE member_id = ? AND status = 'PENDING'", Long.class, memberId);
        reviewService.approve(applicationId);
        long channelId = jdbcTemplate.queryForObject("SELECT id FROM channel WHERE member_id = ?", Long.class, memberId);
        return new ChannelWithKey(channelId, streamKeyService.issue(memberId).streamKey());
    }

    // MediaMTX 1.21이 보내는 모양(스파이크 1절). 받지 않는 필드도 그대로 넣는다
    private ResultActions authorize(String path, String query) throws Exception {
        String body = "{\"ip\":\"172.18.0.1\",\"user\":\"\",\"password\":\"\",\"token\":\"\","
                + "\"action\":\"publish\",\"path\":\"" + path + "\",\"protocol\":\"rtmp\","
                + "\"id\":\"d6e341cb-0000-0000-0000-000000000000\",\"query\":\"" + query + "\","
                + "\"userAgent\":\"FMLE/3.0 (compatible; FMSc/1.0)\"}";
        return mockMvc.perform(post(AUTH_PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Nested
    @DisplayName("PublishAuthIntegration")
    class Integration {

        @Test
        @DisplayName("should return 200 for a publish check without Origin or login when the key of the channel matches")
        void allowsMatchingKey() throws Exception {
            ChannelWithKey channel = channelWithKey();

            authorize("live/" + channel.channelId(), "key=" + channel.key()).andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 401 when the key differs from the channel key")
        void rejectsDifferentKey() throws Exception {
            ChannelWithKey channel = channelWithKey();
            ChannelWithKey other = channelWithKey();

            authorize("live/" + channel.channelId(), "key=" + other.key()).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 401 when the query has no key")
        void rejectsMissingKey() throws Exception {
            ChannelWithKey channel = channelWithKey();

            authorize("live/" + channel.channelId(), "").andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 401 when the channel does not exist")
        void rejectsMissingChannel() throws Exception {
            ChannelWithKey channel = channelWithKey();
            long missingId = jdbcTemplate.queryForObject("SELECT NVL(MAX(id), 0) + 1000 FROM channel", Long.class);

            authorize("live/" + missingId, "key=" + channel.key()).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 401 when the path is not live followed by a number")
        void rejectsOtherPath() throws Exception {
            ChannelWithKey channel = channelWithKey();

            authorize("720/" + channel.channelId(), "key=" + channel.key()).andExpect(status().isUnauthorized());
        }

        // long의 최대값(9223372036854775807)보다 큰 19자리. 숫자로 바꾸다 터지면 401이 아니라 500이 된다
        @Test
        @DisplayName("should return 401 when the path is live followed by a 19-digit number larger than the long range")
        void rejectsChannelIdBeyondLongRange() throws Exception {
            ChannelWithKey channel = channelWithKey();

            authorize("live/9999999999999999999", "key=" + channel.key()).andExpect(status().isUnauthorized());
        }
    }
}
