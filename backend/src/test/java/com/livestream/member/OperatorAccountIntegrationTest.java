package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 진짜 Oracle·Redis 컨테이너를 쓴다. 공용 컨텍스트에는 운영자 값이 없으므로 시작 때는 아무것도 만들지 않는다.
// 운영자는 직접 만든 초기화 객체로 만든다. 운영자 값을 넣은 컨텍스트를 따로 띄우면 Oracle 컨테이너가 하나 더 뜬다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OperatorAccountIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8080";
    private static final String OPERATOR_NICKNAME = "운영자";
    private static final String PASSWORD = "operator1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    // 운영자 닉네임은 하나로 고정이라, DB를 같이 쓰는 테스트끼리 겹치지 않게 매번 지우고 시작한다
    @BeforeEach
    void removeOperatorNickname() {
        jdbcTemplate.update("DELETE FROM member WHERE nickname = ?", OPERATOR_NICKNAME);
    }

    private static String newEmail() {
        return "op" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000) + "@example.com";
    }

    private void runInitializer(MemberMapper mapper, String email, String password) {
        new OperatorAccountInitializer(mapper, passwordEncoder, email, password)
                .run(new DefaultApplicationArguments());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
    }

    private int accountCountOf(String email) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE email = ?", Integer.class, email);
    }

    // 이메일 확인을 처음 한 번만 "없음"으로 답하고 나머지는 진짜 매퍼에 맡긴다.
    // 동시 시작에서 확인을 함께 통과한 상황을 매번 만들어, 진짜 Oracle의 제약 위반 경로를 탄다
    private MemberMapper mapperMissingFirstEmailCheck() {
        AtomicBoolean firstCheck = new AtomicBoolean(true);
        return new MemberMapper() {
            @Override
            public void insert(Member member) {
                memberMapper.insert(member);
            }

            @Override
            public boolean existsByEmail(String email) {
                if (firstCheck.getAndSet(false)) {
                    return false;
                }
                return memberMapper.existsByEmail(email);
            }

            @Override
            public boolean existsByNickname(String nickname) {
                return memberMapper.existsByNickname(nickname);
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

            @Override
            public void updateRole(long id, Role role) {
                memberMapper.updateRole(id, role);
            }
        };
    }

    @Nested
    @DisplayName("OperatorAccountIntegration")
    class Integration {

        @Test
        @DisplayName("should return role OPERATOR from my info when logging in with the operator password after the initializer runs")
        void operatorCanLogInAsOperator() throws Exception {
            String email = newEmail();
            runInitializer(memberMapper, email, PASSWORD);

            Cookie session = login(email, PASSWORD)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getCookie("SESSION");

            mockMvc.perform(get("/api/members/me").cookie(session))
                    .andExpect(jsonPath("$.role").value("OPERATOR"));
        }

        @Test
        @DisplayName("should still log in with the old password when the initializer runs again with a different password")
        void keepsOldPassword() throws Exception {
            String email = newEmail();
            runInitializer(memberMapper, email, PASSWORD);

            runInitializer(memberMapper, email, "changed5678");

            login(email, PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("should keep role MEMBER when a member signed up with the operator email before the initializer runs")
        void keepsExistingMemberRole() {
            String email = newEmail();
            memberService.signUp(new SignUpRequest(email, PASSWORD, "먼저" + ThreadLocalRandom.current().nextInt(1000, 10_000)));

            runInitializer(memberMapper, email, PASSWORD);

            assertThat(memberMapper.findByEmail(email).orElseThrow().getRole()).isEqualTo(Role.MEMBER);
        }

        @Test
        @DisplayName("should leave one account and finish both without exception when two initializers run at once")
        void concurrentInitializersLeaveOneAccount() throws Exception {
            String email = newEmail();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<String> task = () -> {
                    start.await();
                    try {
                        runInitializer(memberMapper, email, PASSWORD);
                        return "OK";
                    } catch (RuntimeException e) {
                        return e.getClass().getSimpleName();
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

            assertThat(accountCountOf(email)).isEqualTo(1);
            assertThat(outcomes).containsExactly("OK", "OK");
        }

        @Test
        @DisplayName("should finish without exception and leave one account when the existence check passes but the account is already saved")
        void toleratesConstraintViolation() {
            String email = newEmail();
            runInitializer(memberMapper, email, PASSWORD);

            runInitializer(mapperMissingFirstEmailCheck(), email, PASSWORD);

            assertThat(accountCountOf(email)).isEqualTo(1);
        }

        @Test
        @DisplayName("should start the application with the initializer registered when operator values are not set")
        void startsWithInitializerRegistered() {
            assertThat(applicationContext.getBeansOfType(OperatorAccountInitializer.class)).hasSize(1);
        }
    }
}
