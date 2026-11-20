package com.livestream.channel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// 진짜 Oracle·Redis 컨테이너와 보안 필터·Spring Session 필터를 모두 거친다.
// 스트리머는 실제 흐름(가입 → 신청 → 운영자 승인)으로 만든다. DB를 테스트끼리 같이 쓰므로 매번 새로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChannelIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8080";
    private static final String PASSWORD = "abcd1234";
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private StreamerApplicationService applicationService;

    @Autowired
    private StreamerApplicationReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newEmail() {
        return "c" + unique() + "@example.com";
    }

    // 로그인 시도는 IP별로도 센다. 테스트끼리 같은 Redis를 쓰므로 요청마다 다른 IP로 보낸다
    private static RequestPostProcessor randomIp() {
        String ip = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256)
                + "." + ThreadLocalRandom.current().nextInt(1, 255);
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long signUp(String email) {
        return memberService.signUp(new SignUpRequest(email, PASSWORD, "채널" + unique())).id();
    }

    // 가입 → 신청 → 승인. 채널 id를 돌려준다
    private long streamerChannel(String email) {
        long memberId = signUp(email);
        applicationService.apply(memberId, Role.MEMBER);
        long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM streamer_application WHERE member_id = ? AND status = 'PENDING'", Long.class, memberId);
        reviewService.approve(applicationId);
        return jdbcTemplate.queryForObject("SELECT id FROM channel WHERE member_id = ?", Long.class, memberId);
    }

    private String nicknameOf(String email) {
        return jdbcTemplate.queryForObject("SELECT nickname FROM member WHERE email = ?", String.class, email);
    }

    private Cookie loggedIn(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(randomIp())
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SESSION_COOKIE);
    }

    private ResultActions update(long channelId, Cookie session, String name, String description) throws Exception {
        return mockMvc.perform(put("/api/channels/{channelId}", channelId)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateChannelRequest(name, description))));
    }

    private ResultActions channelPage(long channelId) throws Exception {
        return mockMvc.perform(get("/api/channels/{channelId}", channelId));
    }

    @Nested
    @DisplayName("ChannelIntegration")
    class Integration {

        @Test
        @DisplayName("should show name, description and offline status to a visitor without login")
        void showsChannelToVisitor() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            jdbcTemplate.update("UPDATE channel SET description = 'hello channel' WHERE id = ?", channelId);

            channelPage(channelId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(nicknameOf(email)))
                    .andExpect(jsonPath("$.description").value("hello channel"))
                    .andExpect(jsonPath("$.live").value(false));
        }

        @Test
        @DisplayName("should return 404 with code CHANNEL_NOT_FOUND when a visitor requests a channel id that does not exist")
        void returnsNotFoundForUnknownChannel() throws Exception {
            long missing = jdbcTemplate.queryForObject("SELECT NVL(MAX(id), 0) + 1000 FROM channel", Long.class);

            channelPage(missing)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        }

        @Test
        @DisplayName("should save a 20-character name and a 200-character description and show them on the channel page when the owner updates")
        void savesLongestValues() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            // 한글은 UTF-8로 3바이트다. 컬럼이 글자 수(CHAR) 기준이어야 들어간다
            String name = "채".repeat(20);
            String description = "소".repeat(200);

            update(channelId, loggedIn(email), name, description).andExpect(status().isOk());

            channelPage(channelId)
                    .andExpect(jsonPath("$.name").value(name))
                    .andExpect(jsonPath("$.description").value(description));
        }

        @Test
        @DisplayName("should save a 2-character name and an empty description and show the description as empty on the channel page")
        void savesShortestValues() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            jdbcTemplate.update("UPDATE channel SET description = 'before' WHERE id = ?", channelId);

            update(channelId, loggedIn(email), "가나", "").andExpect(status().isOk());

            channelPage(channelId)
                    .andExpect(jsonPath("$.name").value("가나"))
                    .andExpect(jsonPath("$.description").value(""));
        }

        @Test
        @DisplayName("should return 403 with code CHANNEL_NOT_OWNER and keep the channel unchanged when another streamer updates it")
        void rejectsAnotherStreamer() throws Exception {
            String ownerEmail = newEmail();
            long channelId = streamerChannel(ownerEmail);
            String otherEmail = newEmail();
            streamerChannel(otherEmail);

            update(channelId, loggedIn(otherEmail), "빼앗은이름", "남의 소개")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CHANNEL_NOT_OWNER"));
            channelPage(channelId).andExpect(jsonPath("$.name").value(nicknameOf(ownerEmail)));
        }

        @Test
        @DisplayName("should return the channel of the logged-in streamer from the studio")
        void returnsStudioChannel() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);

            mockMvc.perform(get("/api/studio/channel").cookie(loggedIn(email)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(channelId))
                    .andExpect(jsonPath("$.name").value(nicknameOf(email)));
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN when a member who is not a streamer opens the studio channel")
        void forbidsStudioForMember() throws Exception {
            String email = newEmail();
            signUp(email);

            mockMvc.perform(get("/api/studio/channel").cookie(loggedIn(email)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }
}
