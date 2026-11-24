package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberService;
import com.livestream.member.Role;
import com.livestream.member.SignUpRequest;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// 재발급이 키를 DB에 커밋한 "다음" MediaMTX에 끊기를 요청하는지 진짜 DB로 본다.
// 가짜 MediaMTX API(JDK HttpServer)를 테스트 설정의 주소(src/test/resources/config/application.yml, :19997)에 띄우고,
// 목록 요청을 받는 순간 다른 DB 연결로 채널의 해시를 읽는다. 커밋 전이면 이전 키의 해시가 보인다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StreamKeyReissueIntegrationTest {

    private static final int FAKE_MEDIAMTX_PORT = 19997;
    private static final String ALLOWED_ORIGIN = "http://localhost:8000";
    private static final String PASSWORD = "abcd1234";
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 설정(src/test/resources/config/application.yml)의 내부 비밀번호
    @Value("${app.mediamtx.api-password}")
    private String internalPassword;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

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

    // 가입 → 신청 → 승인. 채널 id를 돌려준다
    private long streamerChannel(String email) {
        long memberId = memberService.signUp(new SignUpRequest(email, PASSWORD, "재발" + unique())).id();
        applicationService.apply(memberId, Role.MEMBER);
        long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM streamer_application WHERE member_id = ? AND status = 'PENDING'", Long.class, memberId);
        reviewService.approve(applicationId);
        return jdbcTemplate.queryForObject("SELECT id FROM channel WHERE member_id = ?", Long.class, memberId);
    }

    private Cookie loggedIn(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(randomIp())
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("SESSION");
    }

    private String issuedKey(Cookie session) throws Exception {
        String body = mockMvc.perform(post(KEY_PATH).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        return json.get("streamKey").asText();
    }

    @Nested
    @DisplayName("StreamKeyReissueIntegration")
    class Integration {

        @Test
        @DisplayName("should ask MediaMTX to disconnect only after the new key hash is committed when a streamer reissues the key")
        void disconnectsAfterCommit() throws Exception {
            String email = "r" + unique() + "@example.com";
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);
            issuedKey(session);

            // 목록 요청이 오는 순간 DB에 있는 해시. 송출 연결은 없다고 답한다
            AtomicReference<String> hashSeenByMediaMtx = new AtomicReference<>();
            HttpServer fakeMediaMtx = HttpServer.create(new InetSocketAddress("localhost", FAKE_MEDIAMTX_PORT), 0);
            fakeMediaMtx.createContext("/v3/rtmpconns/list", exchange -> {
                hashSeenByMediaMtx.set(jdbcTemplate.queryForObject(
                        "SELECT stream_key_hash FROM channel WHERE id = ?", String.class, channelId));
                byte[] body = "{\"pageCount\":0,\"itemCount\":0,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            fakeMediaMtx.start();
            String newKey;
            try {
                newKey = issuedKey(session);
            } finally {
                fakeMediaMtx.stop(0);
            }

            assertThat(hashSeenByMediaMtx.get()).isEqualTo(sha256Hex(newKey));
        }

        @Test
        @DisplayName("should return 200 with a new key when the MediaMTX API is unreachable")
        void reissuesWhenMediaMtxUnreachable() throws Exception {
            // 가짜 MediaMTX를 띄우지 않는다 → :19997 연결 거부
            String email = "r" + unique() + "@example.com";
            long channelId = streamerChannel(email);
            Cookie session = loggedIn(email);
            issuedKey(session);

            String newKey = issuedKey(session);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT stream_key_hash FROM channel WHERE id = ?", String.class, channelId))
                    .isEqualTo(sha256Hex(newKey));
        }

        @Test
        @DisplayName("should send the internal password as basic auth to the MediaMTX API when a streamer reissues the key")
        void reissueSendsBasicAuth() throws Exception {
            String email = "r" + unique() + "@example.com";
            streamerChannel(email);
            Cookie session = loggedIn(email);
            issuedKey(session);

            AtomicReference<String> authorization = new AtomicReference<>();
            HttpServer fakeMediaMtx = HttpServer.create(new InetSocketAddress("localhost", FAKE_MEDIAMTX_PORT), 0);
            fakeMediaMtx.createContext("/v3/rtmpconns/list", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{\"pageCount\":0,\"itemCount\":0,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            fakeMediaMtx.start();
            try {
                issuedKey(session);
            } finally {
                fakeMediaMtx.stop(0);
            }

            // 내부 사용자 이름은 MediaMtxApiAuthService.INTERNAL_USER(broadcast 패키지 안에서만 보인다)
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString(("spring:" + internalPassword).getBytes(StandardCharsets.UTF_8));
            assertThat(authorization.get()).isEqualTo(expected);
        }
    }
}
