package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

// 진짜 Oracle·Redis 컨테이너와 보안 필터·Spring Session 필터를 모두 거친다.
// DB를 테스트끼리 같이 쓰므로 이메일·닉네임은 매번 새로 만든다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberLoginIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8080";
    private static final String PASSWORD = "abcd1234";
    // Spring Session이 쓰는 쿠키 이름. 값은 세션 ID를 Base64로 감싼 것이다
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private MemberResponse signUp(String email) {
        return memberService.signUp(new SignUpRequest(email, PASSWORD, "로그인" + unique()));
    }

    private ResultActions login(String email, String password, Cookie... cookies) throws Exception {
        var request = post("/api/auth/login")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password)));
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mockMvc.perform(request);
    }

    private MvcResult loggedIn(String email) throws Exception {
        return login(email, PASSWORD).andExpect(status().isOk()).andReturn();
    }

    private static Cookie sessionCookieOf(MvcResult result) {
        return result.getResponse().getCookie(SESSION_COOKIE);
    }

    // 응답에 세션 쿠키가 없으면 null
    private static String sessionIdOf(MvcResult result) {
        Cookie cookie = sessionCookieOf(result);
        return cookie == null ? null : new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    private static Cookie cookieFor(String sessionId) {
        return new Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
    }

    // 공격자가 미리 받아 둔 세션처럼, 로그인 전에 저장소에 세션을 하나 만든다
    private static <S extends Session> String createSession(SessionRepository<S> repository) {
        S session = repository.createSession();
        repository.save(session);
        return session.getId();
    }

    @Nested
    @DisplayName("MemberLoginIntegration")
    class LoginIntegration {

        @Test
        @DisplayName("should set HttpOnly and SameSite=Lax session cookie when login succeeds")
        void setsSessionCookieFlags() throws Exception {
            String email = "l" + unique() + "@example.com";
            signUp(email);

            MvcResult result = loggedIn(email);

            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .anySatisfy(header -> assertThat(header).startsWith(SESSION_COOKIE + "=")
                            .contains("HttpOnly", "SameSite=Lax"));
        }

        @Test
        @DisplayName("should return email, nickname and role from me with the session cookie after login")
        void returnsMeWithSessionCookie() throws Exception {
            String email = "m" + unique() + "@example.com";
            MemberResponse member = signUp(email);
            Cookie cookie = sessionCookieOf(loggedIn(email));

            mockMvc.perform(get("/api/members/me").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email))
                    .andExpect(jsonPath("$.nickname").value(member.nickname()))
                    .andExpect(jsonPath("$.role").value("MEMBER"));
        }

        @Test
        @DisplayName("should log in with a@B.COM when member signed up with A@b.com")
        void logsInIgnoringEmailCase() throws Exception {
            String local = "case" + unique();
            signUp(local.toUpperCase() + "@b.com");

            login(local + "@B.COM", PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("should store session in redis with 7 days max inactive interval when login succeeds")
        void storesSessionWithSevenDayIdleTimeout() throws Exception {
            String email = "r" + unique() + "@example.com";
            signUp(email);

            String sessionId = sessionIdOf(loggedIn(email));

            assertThat(sessionRepository.findById(sessionId).getMaxInactiveInterval()).isEqualTo(Duration.ofDays(7));
        }

        @Test
        @DisplayName("should find the session by member id in session index when login succeeds")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void indexesSessionByMemberId() throws Exception {
            String email = "i" + unique() + "@example.com";
            MemberResponse member = signUp(email);

            String sessionId = sessionIdOf(loggedIn(email));

            assertThat(sessionRepository).isInstanceOfSatisfying(FindByIndexNameSessionRepository.class,
                    indexed -> assertThat(indexed.findByPrincipalName(String.valueOf(member.id())))
                            .containsKey(sessionId));
        }

        @Test
        @DisplayName("should return 401 from me with the same cookie when member logs out")
        void rejectsMeAfterLogout() throws Exception {
            String email = "o" + unique() + "@example.com";
            signUp(email);
            Cookie cookie = sessionCookieOf(loggedIn(email));
            mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(cookie));

            mockMvc.perform(get("/api/members/me").cookie(cookie))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should issue a session id different from the one sent before login when login succeeds")
        void changesSessionIdOnLogin() throws Exception {
            String email = "f" + unique() + "@example.com";
            signUp(email);
            String before = createSession(sessionRepository);

            MvcResult result = login(email, PASSWORD, cookieFor(before)).andExpect(status().isOk()).andReturn();

            assertThat(sessionIdOf(result)).isNotNull().isNotEqualTo(before);
        }

        @Test
        @DisplayName("should return 204 when logout is requested without a session")
        void returnsNoContentForLogoutWithoutSession() throws Exception {
            mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return the same 401 code and message for wrong password and unknown email")
        void returnsSameBodyForWrongPasswordAndUnknownEmail() throws Exception {
            String email = "w" + unique() + "@example.com";
            signUp(email);

            String wrongPassword = login(email, "wrong1234").andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String unknownEmail = login("nobody" + unique() + "@example.com", PASSWORD).andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(wrongPassword).isEqualTo(unknownEmail);
        }

        @Test
        @DisplayName("should return 401 with code UNAUTHENTICATED when me is requested without login")
        void rejectsMeWithoutLogin() throws Exception {
            mockMvc.perform(get("/api/members/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("should return 403 when login request comes from another origin")
        void rejectsLoginFromAnotherOrigin() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .header(HttpHeaders.ORIGIN, "http://evil.example")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest("x@example.com", PASSWORD))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN_ORIGIN"));
        }
    }
}
