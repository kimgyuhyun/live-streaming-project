package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.TestcontainersConfiguration;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.LoginRequest;
import com.livestream.member.MemberMapper;
import com.livestream.member.MemberService;
import com.livestream.member.SignUpRequest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

// 진짜 Oracle·Redis 컨테이너와 보안 필터·Spring Session 필터를 모두 거친다.
// DB를 테스트끼리 같이 쓰므로 회원·신청은 매번 새로 만들고, 대기 목록은 이 테스트가 만든 신청만 골라 본다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StreamerApplicationReviewIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8000";
    private static final String PASSWORD = "abcd1234";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String OPERATOR_PATH = "/api/operator/streamer-applications";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private StreamerApplicationReviewService reviewService;

    @Autowired
    private StreamerApplicationMapper applicationMapper;

    @Autowired
    private ChannelMapper channelMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String unique() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    private static String newEmail() {
        return "r" + unique() + "@example.com";
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

    // 새 회원을 만들고 id를 돌려준다
    private long signUp(String email) {
        return memberService.signUp(new SignUpRequest(email, PASSWORD, "심사" + unique())).id();
    }

    private String nicknameOf(long memberId) {
        return jdbcTemplate.queryForObject("SELECT nickname FROM member WHERE id = ?", String.class, memberId);
    }

    private ResultActions logIn(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(randomIp())
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))));
    }

    private Cookie loggedIn(String email) throws Exception {
        return logIn(email).andExpect(status().isOk()).andReturn().getResponse().getCookie(SESSION_COOKIE);
    }

    // 운영자 계정은 앱 시작 때 환경변수로 만든다(#5). 여기서는 회원을 만들어 역할만 바꾼다
    private Cookie operatorCookie() throws Exception {
        String email = newEmail();
        long memberId = signUp(email);
        jdbcTemplate.update("UPDATE member SET role = 'OPERATOR' WHERE id = ?", memberId);
        return loggedIn(email);
    }

    private long application(long memberId, ApplicationStatus status) {
        StreamerApplication application = new StreamerApplication(memberId, status);
        applicationMapper.insert(application);
        return application.getId();
    }

    private ResultActions approveOverHttp(long applicationId, Cookie session) throws Exception {
        return mockMvc.perform(post(OPERATOR_PATH + "/{id}/approve", applicationId)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session));
    }

    private ResultActions rejectOverHttp(long applicationId, Cookie session) throws Exception {
        return mockMvc.perform(post(OPERATOR_PATH + "/{id}/reject", applicationId)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN).cookie(session));
    }

    private String statusOf(long applicationId) {
        return jdbcTemplate.queryForObject("SELECT status FROM streamer_application WHERE id = ?", String.class,
                applicationId);
    }

    private String roleOf(long memberId) {
        return jdbcTemplate.queryForObject("SELECT role FROM member WHERE id = ?", String.class, memberId);
    }

    private int channelCountOf(long memberId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM channel WHERE member_id = ?", Integer.class,
                memberId);
    }

    @Nested
    @DisplayName("StreamerApplicationReviewIntegration")
    class Integration {

        @Test
        @DisplayName("should list pending applications oldest first with nickname, email and applied time when operator requests the pending list")
        void listsPendingOldestFirst() throws Exception {
            String firstEmail = newEmail();
            String secondEmail = newEmail();
            String thirdEmail = newEmail();
            long first = signUp(firstEmail);
            long second = signUp(secondEmail);
            long third = signUp(thirdEmail);
            // id 순서와 신청 시각 순서를 어긋나게 넣는다. 신청 시각 순으로 나와야 한다
            String insert = "INSERT INTO streamer_application (member_id, status, created_at) VALUES (?, 'PENDING', ?)";
            jdbcTemplate.update(insert, first, Timestamp.valueOf(LocalDateTime.of(2020, 1, 1, 10, 3, 0)));
            jdbcTemplate.update(insert, second, Timestamp.valueOf(LocalDateTime.of(2020, 1, 1, 10, 1, 0)));
            jdbcTemplate.update(insert, third, Timestamp.valueOf(LocalDateTime.of(2020, 1, 1, 10, 2, 0)));

            String body = mockMvc.perform(get(OPERATOR_PATH).cookie(operatorCookie()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            List<List<String>> mine = new ArrayList<>();
            for (JsonNode row : objectMapper.readTree(body)) {
                String email = row.get("email").asText();
                if (List.of(firstEmail, secondEmail, thirdEmail).contains(email)) {
                    mine.add(List.of(row.get("nickname").asText(), email, row.get("appliedAt").asText()));
                }
            }
            assertThat(mine).containsExactly(
                    List.of(nicknameOf(second), secondEmail, "2020-01-01T10:01:00"),
                    List.of(nicknameOf(third), thirdEmail, "2020-01-01T10:02:00"),
                    List.of(nicknameOf(first), firstEmail, "2020-01-01T10:03:00"));
        }

        @Test
        @DisplayName("should leave approved and rejected applications out of the pending list")
        void leavesDecidedOutOfPendingList() {
            long pending = application(signUp(newEmail()), ApplicationStatus.PENDING);
            long approved = application(signUp(newEmail()), ApplicationStatus.APPROVED);
            long rejected = application(signUp(newEmail()), ApplicationStatus.REJECTED);

            List<Long> ids = reviewService.findPending().stream().map(PendingApplicationResponse::id).toList();

            assertThat(ids).contains(pending).doesNotContain(approved, rejected);
        }

        @Test
        @DisplayName("should mark the application approved, make the member STREAMER and create one channel named after the nickname with an empty description when operator approves")
        void approvesOverHttp() throws Exception {
            long memberId = signUp(newEmail());
            long applicationId = application(memberId, ApplicationStatus.PENDING);

            approveOverHttp(applicationId, operatorCookie())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            Map<String, Object> channel = jdbcTemplate.queryForMap(
                    "SELECT name, description FROM channel WHERE member_id = ?", memberId);
            assertThat(List.of(statusOf(applicationId), roleOf(memberId), channel.get("NAME")))
                    .containsExactly("APPROVED", "STREAMER", nicknameOf(memberId));
            // Oracle은 빈 문자열을 NULL로 저장한다. 빈 소개 = NULL
            assertThat(channel.get("DESCRIPTION")).isNull();
        }

        @Test
        @DisplayName("should return 401 for the applicant's existing cookie and STREAMER after logging in again when the application is approved")
        void logsApplicantOutOnApproval() throws Exception {
            String email = newEmail();
            long memberId = signUp(email);
            Cookie applicantSession = loggedIn(email);
            long applicationId = application(memberId, ApplicationStatus.PENDING);

            approveOverHttp(applicationId, operatorCookie()).andExpect(status().isOk());

            mockMvc.perform(get("/api/members/me").cookie(applicantSession))
                    .andExpect(status().isUnauthorized());
            logIn(email).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STREAMER"));
        }

        @Test
        @DisplayName("should mark the application rejected and keep the member role MEMBER when operator rejects")
        void rejectsOverHttp() throws Exception {
            long memberId = signUp(newEmail());
            long applicationId = application(memberId, ApplicationStatus.PENDING);

            rejectOverHttp(applicationId, operatorCookie())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));

            assertThat(List.of(statusOf(applicationId), roleOf(memberId))).containsExactly("REJECTED", "MEMBER");
        }

        @Test
        @DisplayName("should create one channel and reject the other approval when two approvals of the same application arrive at once")
        void createsOneChannelForConcurrentApprovals() throws Exception {
            long memberId = signUp(newEmail());
            long applicationId = application(memberId, ApplicationStatus.PENDING);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<String> task = () -> {
                    start.await();
                    try {
                        reviewService.approve(applicationId);
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

            assertThat(channelCountOf(memberId)).isEqualTo(1);
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "CHANNEL_APPLICATION_ALREADY_DECIDED");
        }

        @Test
        @DisplayName("should reject a second channel for the same member")
        void rejectsSecondChannelForSameMember() {
            long memberId = signUp(newEmail());
            channelMapper.insert(new Channel(memberId, "첫채널", ""));

            assertThatThrownBy(() -> channelMapper.insert(new Channel(memberId, "둘째채널", "")))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @DisplayName("should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED when operator approves an application that is already approved")
        void rejectsApprovingApprovedOverHttp() throws Exception {
            long applicationId = application(signUp(newEmail()), ApplicationStatus.APPROVED);

            approveOverHttp(applicationId, operatorCookie())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHANNEL_APPLICATION_ALREADY_DECIDED"));
        }

        @Test
        @DisplayName("should return 409 with code CHANNEL_APPLICATION_ALREADY_DECIDED when operator rejects an application that is already rejected")
        void rejectsRejectingRejectedOverHttp() throws Exception {
            long applicationId = application(signUp(newEmail()), ApplicationStatus.REJECTED);

            rejectOverHttp(applicationId, operatorCookie())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHANNEL_APPLICATION_ALREADY_DECIDED"));
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_ALREADY_DECIDED and keep the application rejected with no channel when a rejected application is approved")
        void keepsRejectedWhenApproved() {
            long memberId = signUp(newEmail());
            long applicationId = application(memberId, ApplicationStatus.REJECTED);

            assertThatThrownBy(() -> reviewService.approve(applicationId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_ALREADY_DECIDED);
            assertThat(List.of(statusOf(applicationId), roleOf(memberId), String.valueOf(channelCountOf(memberId))))
                    .containsExactly("REJECTED", "MEMBER", "0");
        }

        @Test
        @DisplayName("should throw CHANNEL_APPLICATION_ALREADY_DECIDED and keep the application approved when an approved application is rejected")
        void keepsApprovedWhenRejected() {
            long applicationId = application(signUp(newEmail()), ApplicationStatus.APPROVED);

            assertThatThrownBy(() -> reviewService.reject(applicationId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CHANNEL_APPLICATION_ALREADY_DECIDED);
            assertThat(statusOf(applicationId)).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("should keep the application pending, the member role MEMBER and no channel when channel creation fails during approval")
        void rollsBackWhenChannelCreationFails() {
            long memberId = signUp(newEmail());
            long applicationId = application(memberId, ApplicationStatus.PENDING);
            IllegalStateException failure = new IllegalStateException("channel insert failed");
            ChannelMapper failing = new ChannelMapper() {
                @Override
                public void insert(Channel channel) {
                    throw failure;
                }

                @Override
                public void insertForOperatorsWithoutChannel() {
                    channelMapper.insertForOperatorsWithoutChannel();
                }

                @Override
                public Optional<Channel> findById(long id) {
                    return channelMapper.findById(id);
                }

                @Override
                public Optional<Channel> findByMemberId(long memberId) {
                    return channelMapper.findByMemberId(memberId);
                }

                @Override
                public void update(long id, String name, String description) {
                    channelMapper.update(id, name, description);
                }

                @Override
                public void updateTitle(long id, String title) {
                    channelMapper.updateTitle(id, title);
                }

                @Override
                public void markDemo(long id) {
                    channelMapper.markDemo(id);
                }

                @Override
                public void increaseFollowerCount(long id) {
                    channelMapper.increaseFollowerCount(id);
                }

                @Override
                public void decreaseFollowerCount(long id) {
                    channelMapper.decreaseFollowerCount(id);
                }

                @Override
                public void updateStreamKey(long id, String hash, String prefix) {
                    channelMapper.updateStreamKey(id, hash, prefix);
                }
            };
            StreamerApplicationReviewService service = new StreamerApplicationReviewService(
                    applicationMapper, memberMapper, failing, transactionTemplate, sessionRepository);

            assertThatThrownBy(() -> service.approve(applicationId)).isSameAs(failure);
            assertThat(List.of(statusOf(applicationId), roleOf(memberId), String.valueOf(channelCountOf(memberId))))
                    .containsExactly("PENDING", "MEMBER", "0");
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN when a member who is not an operator requests the pending list")
        void forbidsPendingListForMember() throws Exception {
            String email = newEmail();
            signUp(email);

            mockMvc.perform(get(OPERATOR_PATH).cookie(loggedIn(email)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN and keep the application pending when a member who is not an operator approves")
        void forbidsApprovalByMember() throws Exception {
            String email = newEmail();
            long memberId = signUp(email);
            long applicationId = application(memberId, ApplicationStatus.PENDING);

            approveOverHttp(applicationId, loggedIn(email))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            assertThat(statusOf(applicationId)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN and keep the application pending when a member who is not an operator rejects")
        void forbidsRejectionByMember() throws Exception {
            String email = newEmail();
            long memberId = signUp(email);
            long applicationId = application(memberId, ApplicationStatus.PENDING);

            rejectOverHttp(applicationId, loggedIn(email))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            assertThat(statusOf(applicationId)).isEqualTo("PENDING");
        }
    }
}
