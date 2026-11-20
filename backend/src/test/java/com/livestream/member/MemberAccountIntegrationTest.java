package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 진짜 Oracle·Redis 컨테이너와 보안 필터·Spring Session 필터를 모두 거친다.
// DB·Redis를 테스트끼리 같이 쓰므로 이메일·닉네임은 매번 새로 만들고, 로그인은 요청마다 다른 IP로 보낸다
// (같은 IP를 쓰면 다른 테스트의 로그인 실패가 IP 카운터에 쌓인다).
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberAccountIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8080";
    private static final String PASSWORD = "abcd1234";
    private static final String NEW_PASSWORD = "newpass99";
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private String newEmail() {
        String email = "acct" + unique() + "@example.com";
        memberService.signUp(new SignUpRequest(email, PASSWORD, "계정" + unique()));
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        String ip = newIp();
        return mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
    }

    // 로그인할 때마다 새 기기처럼 새 세션 쿠키를 받는다
    private Cookie device(String email) throws Exception {
        return login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getCookie(SESSION_COOKIE);
    }

    private ResultActions changeNickname(Cookie cookie, String nickname) throws Exception {
        var request = put("/api/members/me/nickname")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ChangeNicknameRequest(nickname)));
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    // 현재 비밀번호 확인도 IP별로 센다. 다른 테스트의 실패가 같은 IP에 쌓이지 않게 매번 새 IP로 보낸다
    private ResultActions changePassword(Cookie cookie, String currentPassword, String newPassword) throws Exception {
        return changePassword(cookie, currentPassword, newPassword, newIp());
    }

    private ResultActions changePassword(Cookie cookie, String currentPassword, String newPassword, String ip)
            throws Exception {
        return mockMvc.perform(put("/api/members/me/password")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ChangePasswordRequest(currentPassword, newPassword))));
    }

    private ResultActions me(Cookie cookie) throws Exception {
        return mockMvc.perform(get("/api/members/me").cookie(cookie));
    }

    // 미리 조회만 늘 "없음"으로 답하고 나머지는 진짜 매퍼에 맡긴다.
    // 동시 변경에서 조회를 함께 통과한 상황을 매번 만들어, 진짜 Oracle의 UNIQUE 위반 경로를 탄다
    private MemberService serviceSkippingNicknamePreCheck() {
        MemberMapper skipping = mock(MemberMapper.class, AdditionalAnswers.delegatesTo(memberMapper));
        doReturn(false).when(skipping).existsByNickname(anyString());
        return new MemberService(skipping, passwordEncoder, loginAttemptLimiter, sessionRepository);
    }

    @Nested
    @DisplayName("MemberAccountIntegration")
    class AccountIntegration {

        @Test
        @DisplayName("should show the new nickname in my info after changing it")
        void showsNewNickname() throws Exception {
            Cookie cookie = device(newEmail());
            String nickname = "새" + unique();
            changeNickname(cookie, nickname).andExpect(status().isOk());

            me(cookie).andExpect(jsonPath("$.nickname").value(nickname));
        }

        @Test
        @DisplayName("should return 401 to device B and 200 to device A after A changes the password")
        void logsOutOtherDevices() throws Exception {
            String email = newEmail();
            Cookie deviceA = device(email);
            Cookie deviceB = device(email);

            changePassword(deviceA, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

            me(deviceB).andExpect(status().isUnauthorized());
            me(deviceA).andExpect(status().isOk());
        }

        @Test
        @DisplayName("should log in with the new password after the change")
        void logsInWithNewPassword() throws Exception {
            String email = newEmail();
            changePassword(device(email), PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

            login(email, NEW_PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("should accept only one of two members changing to the same nickname at once")
        void acceptsOneOfConcurrentNicknameChanges() throws Exception {
            MemberService service = serviceSkippingNicknamePreCheck();
            long first = memberMapper.findByEmail(newEmail()).orElseThrow().getId();
            long second = memberMapper.findByEmail(newEmail()).orElseThrow().getId();
            String nickname = "동시" + unique();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<MemberResponse>> results = new ArrayList<>();
            for (long id : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return service.changeNickname(id, new ChangeNicknameRequest(nickname));
                }));
            }
            start.countDown();
            List<ErrorCode> failures = new ArrayList<>();
            for (Future<MemberResponse> result : results) {
                try {
                    result.get();
                } catch (ExecutionException e) {
                    failures.add(e.getCause() instanceof BusinessException b ? b.getCode() : null);
                }
            }
            pool.shutdown();

            assertThat(failures).containsExactly(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }

        @Test
        @DisplayName("should keep the old password when the change is rejected for a wrong current password")
        void keepsOldPasswordWhenRejected() throws Exception {
            String email = newEmail();
            changePassword(device(email), "wrong1234", NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MEMBER_PASSWORD_MISMATCH"));

            login(email, PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("should reject the old password with 401 after the change")
        void rejectsOldPassword() throws Exception {
            String email = newEmail();
            changePassword(device(email), PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

            login(email, PASSWORD).andExpect(status().isUnauthorized());
        }

        // 로그인 시도 제한(이메일 5번)을 비밀번호 변경의 현재 비밀번호 확인에도 건다. 6번째는 맞는 비밀번호여도 막힌다
        @Test
        @DisplayName("should return 429 on a password change after five wrong current passwords")
        void blocksPasswordChangeAfterFiveWrongCurrentPasswords() throws Exception {
            Cookie cookie = device(newEmail());
            String ip = newIp();
            for (int i = 0; i < LoginAttemptLimiter.EMAIL_LIMIT; i++) {
                changePassword(cookie, "wrong1234", NEW_PASSWORD, ip).andExpect(status().isBadRequest());
            }

            changePassword(cookie, PASSWORD, NEW_PASSWORD, ip)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("MEMBER_LOGIN_BLOCKED"));
        }

        @Test
        @DisplayName("should return 401 when changing nickname without logging in")
        void rejectsAnonymousNicknameChange() throws Exception {
            changeNickname(null, "익명" + unique()).andExpect(status().isUnauthorized());
        }
    }
}
