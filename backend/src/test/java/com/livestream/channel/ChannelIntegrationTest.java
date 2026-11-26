package com.livestream.channel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.broadcast.BroadcastService;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import java.util.EnumSet;
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

    private static final String ALLOWED_ORIGIN = "http://localhost:8000";
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

    // 스튜디오 API는 약관에 동의해야 열린다(#35)
    @Autowired
    private StudioTermsService studioTermsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 진행 중 방송은 송출 시작 알림과 같은 처리로 만든다(#11)
    @Autowired
    private BroadcastService broadcastService;

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
        studioTermsService.agree(memberId, EnumSet.allOf(StudioTerms.class));
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

    private ResultActions setTitle(Cookie session, String title) throws Exception {
        return mockMvc.perform(put("/api/studio/title")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateTitleRequest(title))));
    }

    private ResultActions channelPage(long channelId) throws Exception {
        return mockMvc.perform(get("/api/channels/{channelId}", channelId));
    }

    // 운영자 계정은 앱 시작 때 환경변수로 만든다(#5). 여기서는 회원을 만들어 역할만 바꾼다
    private Cookie operatorCookie() throws Exception {
        String email = newEmail();
        long memberId = signUp(email);
        jdbcTemplate.update("UPDATE member SET role = 'OPERATOR' WHERE id = ?", memberId);
        return loggedIn(email);
    }

    private ResultActions markDemo(long channelId, Cookie session) throws Exception {
        var request = put("/api/operator/channels/{channelId}/demo", channelId)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
        return mockMvc.perform(session == null ? request : request.cookie(session));
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

        @Test
        @DisplayName("should save a 1-character title and show it on the channel page")
        void savesOneCharacterTitle() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);

            setTitle(loggedIn(email), "가").andExpect(status().isOk());

            channelPage(channelId).andExpect(jsonPath("$.title").value("가"));
        }

        @Test
        @DisplayName("should save a 50-character title and show it on the channel page")
        void savesFiftyCharacterTitle() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            // 한글은 UTF-8로 3바이트다. 컬럼이 글자 수(CHAR) 기준이어야 들어간다
            String title = "제".repeat(50);

            setTitle(loggedIn(email), title).andExpect(status().isOk());

            channelPage(channelId).andExpect(jsonPath("$.title").value(title));
        }

        @Test
        @DisplayName("should show 하늘의 방송 as the title on the channel page when the channel 하늘 has never set a title")
        void showsDefaultTitle() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            update(channelId, loggedIn(email), "하늘", "").andExpect(status().isOk());

            channelPage(channelId).andExpect(jsonPath("$.title").value("하늘의 방송"));
        }

        @Test
        @DisplayName("should show the new title on the channel page when the streamer changes the title during a live broadcast")
        void showsNewTitleDuringLiveBroadcast() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);
            setTitle(session, "before").andExpect(status().isOk());
            broadcastService.onAvailable("live/" + channelId);
            channelPage(channelId).andExpect(jsonPath("$.live").value(true));

            setTitle(session, "after").andExpect(status().isOk());

            channelPage(channelId).andExpect(jsonPath("$.title").value("after"));
        }

        @Test
        @DisplayName("should return live true from the studio channel when the channel has a live broadcast")
        void returnsStudioLive() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            broadcastService.onAvailable("live/" + channelId);

            mockMvc.perform(get("/api/studio/channel").cookie(loggedIn(email)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.live").value(true));
        }

        @Test
        @DisplayName("should return live false from the studio channel when the channel has no live broadcast")
        void returnsStudioOffline() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            // 방송을 한 번 하고 끝낸 채널. 지난 방송 기록이 있어도 진행 중이 아니면 송출 없음이다
            broadcastService.onAvailable("live/" + channelId);
            broadcastService.onUnavailable("live/" + channelId);

            mockMvc.perform(get("/api/studio/channel").cookie(loggedIn(email)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.live").value(false));
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends a 51-character title")
        void rejectsFiftyOneCharacterTitle() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);
            setTitle(session, "before").andExpect(status().isOk());

            setTitle(session, "제".repeat(51))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            channelPage(channelId).andExpect(jsonPath("$.title").value("before"));
        }

        @Test
        @DisplayName("should return 400 with code INVALID_INPUT and keep the previous title when the streamer sends a title of only spaces")
        void rejectsBlankTitle() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);
            setTitle(session, "before").andExpect(status().isOk());

            setTitle(session, "   ")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            channelPage(channelId).andExpect(jsonPath("$.title").value("before"));
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN when a member who is not a streamer sets a title")
        void forbidsTitleForMember() throws Exception {
            String email = newEmail();
            signUp(email);

            setTitle(loggedIn(email), "오늘은 노래")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // 데모 채널 표시(#14). 준비 스크립트가 운영자로 부른다
    @Nested
    @DisplayName("ChannelOperatorController.markDemo")
    class MarkDemo {

        @Test
        @DisplayName("should show the channel as demo to a visitor after an operator marks it")
        void showsDemoToVisitor() throws Exception {
            long channelId = streamerChannel(newEmail());

            markDemo(channelId, operatorCookie()).andExpect(status().isOk());

            channelPage(channelId).andExpect(jsonPath("$.demo").value(true));
        }

        @Test
        @DisplayName("should report a newly approved channel as not demo")
        void newChannelIsNotDemo() throws Exception {
            long channelId = streamerChannel(newEmail());

            channelPage(channelId).andExpect(jsonPath("$.demo").value(false));
        }

        @Test
        @DisplayName("should keep the channel demo when an operator marks it twice")
        void marksTwice() throws Exception {
            long channelId = streamerChannel(newEmail());
            Cookie operator = operatorCookie();
            markDemo(channelId, operator).andExpect(status().isOk());

            markDemo(channelId, operator)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.demo").value(true));
        }

        @Test
        @DisplayName("should return 403 when a member who is not an operator marks a channel as demo")
        void forbidsMember() throws Exception {
            long channelId = streamerChannel(newEmail());
            String memberEmail = newEmail();
            signUp(memberEmail);

            markDemo(channelId, loggedIn(memberEmail))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("should return 403 when the streamer who owns the channel marks it as demo")
        void forbidsOwner() throws Exception {
            String ownerEmail = newEmail();
            long channelId = streamerChannel(ownerEmail);

            markDemo(channelId, loggedIn(ownerEmail))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("should return 401 when a visitor who is not logged in marks a channel as demo")
        void rejectsVisitor() throws Exception {
            long channelId = streamerChannel(newEmail());

            markDemo(channelId, null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }
}
