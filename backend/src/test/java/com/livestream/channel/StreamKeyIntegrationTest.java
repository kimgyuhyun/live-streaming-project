package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import java.util.EnumSet;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
// 스트리머는 실제 흐름(가입 → 신청 → 운영자 승인)으로 만든다. DB를 테스트끼리 같이 쓰므로 매번 새로 만든다.
// OBS 서버는 application.yml의 기본값(rtmp://localhost/live)을 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StreamKeyIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8000";
    private static final String PASSWORD = "abcd1234";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String KEY_PATH = "/api/studio/stream-key";

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

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newEmail() {
        return "k" + unique() + "@example.com";
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

    private static String sha256Hex(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private long signUp(String email) {
        return memberService.signUp(new SignUpRequest(email, PASSWORD, "키" + unique())).id();
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

    private Cookie loggedIn(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(randomIp())
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SESSION_COOKIE);
    }

    private ResultActions issue(Cookie session) throws Exception {
        return mockMvc.perform(post(KEY_PATH).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session));
    }

    private JsonNode issuedKey(Cookie session) throws Exception {
        String body = issue(session).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private Map<String, Object> storedKeyOf(long channelId) {
        return jdbcTemplate.queryForMap(
                "SELECT stream_key_hash, stream_key_prefix FROM channel WHERE id = ?", channelId);
    }

    @Nested
    @DisplayName("StreamKeyIntegration")
    class Integration {

        @Test
        @DisplayName("should return the full key and store only its SHA-256 hash and first 4 characters when a streamer without a key issues one")
        void storesHashAndPrefixOnly() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);

            String key = issuedKey(loggedIn(email)).get("streamKey").asText();

            Map<String, Object> stored = storedKeyOf(channelId);
            assertThat(List.of(stored.get("STREAM_KEY_HASH"), stored.get("STREAM_KEY_PREFIX")))
                    .containsExactly(sha256Hex(key), key.substring(0, 4));
        }

        @Test
        @DisplayName("should return only the first 4 characters and never the full key when a streamer with a key views it in the studio")
        void showsPrefixOnly() throws Exception {
            String email = newEmail();
            streamerChannel(email);
            Cookie session = loggedIn(email);
            String key = issuedKey(session).get("streamKey").asText();

            String body = mockMvc.perform(get(KEY_PATH).cookie(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keyPrefix").value(key.substring(0, 4)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            // 앞 4자리 뒤의 어떤 조각도 응답에 없어야 한다
            assertThat(body).doesNotContain(key).doesNotContain(key.substring(4, 12));
        }

        @Test
        @DisplayName("should store the hash of the new key when a streamer with a key reissues it")
        void storesNewHashOnReissue() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);
            String oldKey = issuedKey(session).get("streamKey").asText();

            String newKey = issuedKey(session).get("streamKey").asText();

            assertThat(newKey).isNotEqualTo(oldKey);
            assertThat(storedKeyOf(channelId).get("STREAM_KEY_HASH")).isEqualTo(sha256Hex(newKey));
        }

        @Test
        @DisplayName("should return the configured OBS server and the stream key field value with the channel id when a streamer issues a key")
        void returnsObsSettings() throws Exception {
            String email = newEmail();
            long channelId = streamerChannel(email);

            JsonNode issued = issuedKey(loggedIn(email));

            assertThat(List.of(issued.get("obsServer").asText(), issued.get("obsStreamKey").asText()))
                    .containsExactly("rtmp://localhost/live", channelId + "?key=" + issued.get("streamKey").asText());
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN when a member who is not a streamer issues a key")
        void forbidsIssueForMember() throws Exception {
            String email = newEmail();
            signUp(email);

            issue(loggedIn(email))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN when a member who is not a streamer views the key")
        void forbidsViewForMember() throws Exception {
            String email = newEmail();
            signUp(email);

            mockMvc.perform(get(KEY_PATH).cookie(loggedIn(email)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("should return 401 with code UNAUTHENTICATED when a visitor without login issues a key")
        void rejectsIssueWithoutLogin() throws Exception {
            mockMvc.perform(post(KEY_PATH).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("should return 401 with code UNAUTHENTICATED when a visitor without login views the key")
        void rejectsViewWithoutLogin() throws Exception {
            mockMvc.perform(get(KEY_PATH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }
}
