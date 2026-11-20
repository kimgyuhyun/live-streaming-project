package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;

// 진짜 Oracle·Redis 컨테이너 위에서 저장·동시성·보안 필터 연결을 본다.
// DB를 테스트끼리 같이 쓰므로 이메일·닉네임은 매번 새로 만든다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberSignUpIntegrationTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    // 미리 조회하는 단계만 늘 "없음"으로 답하고 나머지는 진짜 매퍼에 맡긴다.
    // 동시 가입에서 조회를 함께 통과한 상황을 매번 만들어, 진짜 Oracle의 UNIQUE 위반 경로를 탄다
    private MemberService serviceSkippingPreCheck() {
        MemberMapper skipping = new MemberMapper() {
            @Override
            public void insert(Member member) {
                memberMapper.insert(member);
            }

            @Override
            public boolean existsByEmail(String email) {
                return false;
            }

            @Override
            public boolean existsByNickname(String nickname) {
                return false;
            }

            @Override
            public Optional<Member> findByEmail(String email) {
                return memberMapper.findByEmail(email);
            }

            @Override
            public Optional<Member> findById(long id) {
                return memberMapper.findById(id);
            }

            @Override
            public void updateNickname(long id, String nickname) {
                memberMapper.updateNickname(id, nickname);
            }

            @Override
            public void updatePasswordHash(long id, String passwordHash) {
                memberMapper.updatePasswordHash(id, passwordHash);
            }
        };
        return new MemberService(skipping, passwordEncoder, loginAttemptLimiter, sessionRepository);
    }

    @Nested
    @DisplayName("MemberSignUpIntegration")
    class SignUpIntegration {

        @Test
        @DisplayName("should store bcrypt hash and not raw password when member signs up")
        void storesBcryptHash() {
            String email = "v" + unique() + "@example.com";

            memberService.signUp(new SignUpRequest(email, "abcd1234", "회원" + unique()));

            String stored = memberMapper.findByEmail(email).orElseThrow().getPasswordHash();
            assertThat(new BCryptPasswordEncoder().matches("abcd1234", stored)).isTrue();
        }

        @Test
        @DisplayName("should create one member and reject the other with MEMBER_NICKNAME_DUPLICATED when two sign ups with same nickname arrive at once")
        void createsOneMemberForConcurrentSameNickname() throws Exception {
            String nickname = "동시" + unique();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String email = "c" + i + unique() + "@example.com";
                Callable<String> task = () -> {
                    start.await();
                    try {
                        memberService.signUp(new SignUpRequest(email, "abcd1234", nickname));
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getCode().name();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }
            pool.shutdown();

            assertThat(outcomes).containsExactlyInAnyOrder("OK", "MEMBER_NICKNAME_DUPLICATED");
        }

        @Test
        @DisplayName("should return 403 when sign up request comes from another origin")
        void rejectsOtherOrigin() throws Exception {
            String body = objectMapper.writeValueAsString(
                    new SignUpRequest("o" + unique() + "@example.com", "abcd1234", "출처" + unique()));

            mockMvc.perform(post("/api/members")
                            .header("Origin", "http://evil.example")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN_ORIGIN"));
        }

        @Test
        @DisplayName("should return 201 and persist role MEMBER when sign up request comes from allowed origin")
        void createsMemberThroughSecurityChain() throws Exception {
            String email = "a" + unique() + "@example.com";
            String body = objectMapper.writeValueAsString(new SignUpRequest(email, "abcd1234", "허용" + unique()));

            mockMvc.perform(post("/api/members")
                            .header("Origin", "http://localhost:8080")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());

            assertThat(memberMapper.findByEmail(email).orElseThrow().getRole()).isEqualTo(Role.MEMBER);
        }

        @Test
        @DisplayName("should return MEMBER_NICKNAME_DUPLICATED when pre-check passes but database rejects duplicate nickname")
        void mapsRealNicknameConstraintViolation() {
            String nickname = "제약" + unique();
            memberService.signUp(new SignUpRequest("n1" + unique() + "@example.com", "abcd1234", nickname));

            assertThatThrownBy(() -> serviceSkippingPreCheck()
                    .signUp(new SignUpRequest("n2" + unique() + "@example.com", "abcd1234", nickname)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }

        @Test
        @DisplayName("should return MEMBER_EMAIL_DUPLICATED when pre-check passes but database rejects duplicate email")
        void mapsRealEmailConstraintViolation() {
            String email = "e" + unique() + "@example.com";
            memberService.signUp(new SignUpRequest(email, "abcd1234", "이메일" + unique()));

            assertThatThrownBy(() -> serviceSkippingPreCheck()
                    .signUp(new SignUpRequest(email, "abcd1234", "다른" + unique())))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_EMAIL_DUPLICATED);
        }
    }
}
