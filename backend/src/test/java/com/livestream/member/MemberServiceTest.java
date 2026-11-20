package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.common.error.RetryLaterException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

class MemberServiceTest {

    private MemberMapper memberMapper;
    private MemberService memberService;

    private static final String IP = "203.0.113.10";
    private LoginAttemptLimiter loginAttemptLimiter;
    private FindByIndexNameSessionRepository<Session> sessionRepository;

    @BeforeEach
    void setUp() {
        memberMapper = mock(MemberMapper.class);
        loginAttemptLimiter = mock(LoginAttemptLimiter.class);
        sessionRepository = mockSessionRepository();
        memberService = new MemberService(memberMapper, new BCryptPasswordEncoder(), loginAttemptLimiter, sessionRepository);
        // insert는 DB가 만든 id를 채워 준다
        willAnswer(invocation -> {
            invocation.<Member>getArgument(0).setId(1L);
            return null;
        }).given(memberMapper).insert(any(Member.class));
    }

    private Member insertedMember() {
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("signUp")
    class SignUp {

        @Test
        @DisplayName("should create member with role MEMBER when email and nickname are new")
        void createsMemberWithRoleMember() {
            memberService.signUp(new SignUpRequest("viewer@example.com", "abcd1234", "시청자1"));

            assertThat(insertedMember().getRole()).isEqualTo(Role.MEMBER);
        }

        @Test
        @DisplayName("should save lower-cased email when email contains upper case letters")
        void savesLowerCasedEmail() {
            memberService.signUp(new SignUpRequest("Viewer@Example.COM", "abcd1234", "시청자1"));

            assertThat(insertedMember().getEmail()).isEqualTo("viewer@example.com");
        }

        @Test
        @DisplayName("should throw MEMBER_EMAIL_DUPLICATED when lower-cased email already exists")
        void rejectsExistingEmailIgnoringCase() {
            given(memberMapper.existsByEmail("a@b.com")).willReturn(true);

            assertThatThrownBy(() -> memberService.signUp(new SignUpRequest("A@b.com", "abcd1234", "시청자1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_EMAIL_DUPLICATED);
        }

        @Test
        @DisplayName("should throw MEMBER_NICKNAME_DUPLICATED when nickname already exists")
        void rejectsExistingNickname() {
            given(memberMapper.existsByNickname("시청자1")).willReturn(true);

            assertThatThrownBy(() -> memberService.signUp(new SignUpRequest("viewer@example.com", "abcd1234", "시청자1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }

        // 제약 이름은 V1 마이그레이션에서 UK_MEMBER_EMAIL / UK_MEMBER_NICKNAME으로 붙인다
        @Test
        @DisplayName("should throw MEMBER_EMAIL_DUPLICATED when database rejects duplicate email")
        void mapsEmailConstraintViolation() {
            willThrow(new DuplicateKeyException("ORA-00001: unique constraint (APP.UK_MEMBER_EMAIL) violated"))
                    .given(memberMapper).insert(any(Member.class));

            assertThatThrownBy(() -> memberService.signUp(new SignUpRequest("viewer@example.com", "abcd1234", "시청자1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_EMAIL_DUPLICATED);
        }

        @Test
        @DisplayName("should throw MEMBER_NICKNAME_DUPLICATED when database rejects duplicate nickname")
        void mapsNicknameConstraintViolation() {
            willThrow(new DuplicateKeyException("ORA-00001: unique constraint (APP.UK_MEMBER_NICKNAME) violated"))
                    .given(memberMapper).insert(any(Member.class));

            assertThatThrownBy(() -> memberService.signUp(new SignUpRequest("viewer@example.com", "abcd1234", "시청자1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        private static final String EMAIL = "viewer@example.com";
        private static final String PASSWORD = "abcd1234";

        @BeforeEach
        void givenMember() {
            given(memberMapper.findByEmail(EMAIL)).willReturn(Optional.of(storedMember()));
        }

        private Member storedMember() {
            Member member = new Member(EMAIL, new BCryptPasswordEncoder().encode(PASSWORD), "시청자1", Role.MEMBER);
            member.setId(1L);
            return member;
        }

        @Test
        @DisplayName("should return member when password matches")
        void returnsMemberWhenPasswordMatches() {
            MemberResponse result = memberService.login(new LoginRequest(EMAIL, PASSWORD), IP);

            assertThat(result).isEqualTo(new MemberResponse(1L, EMAIL, "시청자1", Role.MEMBER));
        }

        // 목은 소문자 이메일로 찾을 때만 회원을 돌려준다
        @Test
        @DisplayName("should find member by lower-cased email when email contains upper case letters")
        void findsMemberByLowerCasedEmail() {
            MemberResponse result = memberService.login(new LoginRequest("Viewer@Example.COM", PASSWORD), IP);

            assertThat(result.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should throw MEMBER_LOGIN_FAILED when password does not match")
        void rejectsWrongPassword() {
            assertThatThrownBy(() -> memberService.login(new LoginRequest(EMAIL, "wrong1234"), IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_LOGIN_FAILED);
        }

        @Test
        @DisplayName("should throw MEMBER_LOGIN_FAILED when email does not exist")
        void rejectsUnknownEmail() {
            assertThatThrownBy(() -> memberService.login(new LoginRequest("nobody@example.com", PASSWORD), IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_LOGIN_FAILED);
        }

        // 비교를 건너뛰면 응답이 빨라져 "이 이메일은 없다"가 응답 시간으로 드러난다
        @Test
        @DisplayName("should compare password against a dummy hash once when email does not exist")
        void comparesDummyHashOnceForUnknownEmail() {
            PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
            MemberService service = new MemberService(memberMapper, encoder, loginAttemptLimiter, sessionRepository);

            catchThrowable(() -> service.login(new LoginRequest("nobody@example.com", PASSWORD), IP));

            verify(encoder, times(1)).matches(eq(PASSWORD), anyString());
        }

        // 빈 문자열이나 형식이 틀린 해시면 BCrypt가 계산 없이 바로 false를 돌려줘 응답 시간 차이가 다시 생긴다
        @Test
        @DisplayName("should compare against a bcrypt hash when email does not exist")
        void comparesAgainstBcryptHashForUnknownEmail() {
            PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
            MemberService service = new MemberService(memberMapper, encoder, loginAttemptLimiter, sessionRepository);
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);

            catchThrowable(() -> service.login(new LoginRequest("nobody@example.com", PASSWORD), IP));

            verify(encoder).matches(eq(PASSWORD), hash.capture());
            assertThat(hash.getValue()).matches("\\$2[aby]?\\$\\d{2}\\$[./0-9A-Za-z]{53}");
        }

        // 비교 전에 세야 동시에 들어온 요청도 상한까지만 비교한다 (PRD member M3)
        @Test
        @DisplayName("should count the attempt before comparing password")
        void countsBeforeComparing() {
            PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
            given(memberMapper.findByEmail(EMAIL)).willReturn(Optional.of(storedMember()));
            MemberService service = new MemberService(memberMapper, encoder, loginAttemptLimiter, sessionRepository);

            service.login(new LoginRequest(EMAIL, PASSWORD), IP);

            InOrder order = inOrder(loginAttemptLimiter, encoder);
            order.verify(loginAttemptLimiter).acquire(EMAIL, IP);
            order.verify(encoder).matches(eq(PASSWORD), anyString());
        }

        @Test
        @DisplayName("should report success to the limiter when password matches")
        void reportsSuccess() {
            memberService.login(new LoginRequest(EMAIL, PASSWORD), IP);

            verify(loginAttemptLimiter).succeeded(EMAIL, IP);
        }

        @Test
        @DisplayName("should not compare password when attempts are blocked")
        void skipsComparisonWhenBlocked() {
            PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
            MemberService service = new MemberService(memberMapper, encoder, loginAttemptLimiter, sessionRepository);
            willThrow(new RetryLaterException(ErrorCode.MEMBER_LOGIN_BLOCKED, 600))
                    .given(loginAttemptLimiter).acquire(EMAIL, IP);

            catchThrowable(() -> service.login(new LoginRequest(EMAIL, PASSWORD), IP));

            verify(encoder, never()).matches(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("findMe")
    class FindMe {

        @Test
        @DisplayName("should return email, nickname and role when member exists")
        void returnsMember() {
            Member member = new Member("viewer@example.com", "hash", "시청자1", Role.STREAMER);
            member.setId(7L);
            given(memberMapper.findById(7L)).willReturn(Optional.of(member));

            MemberResponse result = memberService.findMe(7L);

            assertThat(result).isEqualTo(new MemberResponse(7L, "viewer@example.com", "시청자1", Role.STREAMER));
        }
    }

    @SuppressWarnings("unchecked")
    private static FindByIndexNameSessionRepository<Session> mockSessionRepository() {
        return mock(FindByIndexNameSessionRepository.class);
    }

    // 계정 정보 변경에서 쓰는 저장된 회원. 비밀번호는 CURRENT_PASSWORD의 BCrypt 해시
    private static final long MEMBER_ID = 1L;
    private static final String CURRENT_PASSWORD = "abcd1234";
    private static final String NEW_PASSWORD = "newpass99";

    private Member savedMember() {
        Member member = new Member("viewer@example.com", new BCryptPasswordEncoder().encode(CURRENT_PASSWORD),
                "시청자1", Role.MEMBER);
        member.setId(MEMBER_ID);
        return member;
    }

    @Nested
    @DisplayName("changeNickname")
    class ChangeNickname {

        @BeforeEach
        void setUp() {
            given(memberMapper.findById(MEMBER_ID)).willReturn(Optional.of(savedMember()));
        }

        @Test
        @DisplayName("should save and return the new nickname when no other member uses it")
        void savesNewNickname() {
            MemberResponse result = memberService.changeNickname(MEMBER_ID, new ChangeNicknameRequest("새닉네임"));

            verify(memberMapper).updateNickname(MEMBER_ID, "새닉네임");
            assertThat(result.nickname()).isEqualTo("새닉네임");
        }

        @Test
        @DisplayName("should return the member unchanged when the new nickname is the current one")
        void keepsCurrentNickname() {
            given(memberMapper.existsByNickname("시청자1")).willReturn(true);

            MemberResponse result = memberService.changeNickname(MEMBER_ID, new ChangeNicknameRequest("시청자1"));

            assertThat(result.nickname()).isEqualTo("시청자1");
        }

        // 다른 회원과 동시에 바꿔 미리 조회를 함께 통과한 경우. DB UNIQUE 제약이 막는다
        @Test
        @DisplayName("should throw MEMBER_NICKNAME_DUPLICATED when the unique constraint rejects the update")
        void mapsUniqueViolation() {
            willThrow(new DuplicateKeyException("ORA-00001: unique constraint (APP.UK_MEMBER_NICKNAME) violated"))
                    .given(memberMapper).updateNickname(MEMBER_ID, "새닉네임");

            assertThatThrownBy(() -> memberService.changeNickname(MEMBER_ID, new ChangeNicknameRequest("새닉네임")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }

        @Test
        @DisplayName("should throw MEMBER_NICKNAME_DUPLICATED when another member uses the nickname")
        void rejectsTakenNickname() {
            given(memberMapper.existsByNickname("남의닉네임")).willReturn(true);

            assertThatThrownBy(() -> memberService.changeNickname(MEMBER_ID, new ChangeNicknameRequest("남의닉네임")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_NICKNAME_DUPLICATED);
        }
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        private static final String CURRENT_SESSION = "current-session";

        @BeforeEach
        void setUp() {
            given(memberMapper.findById(MEMBER_ID)).willReturn(Optional.of(savedMember()));
            given(sessionRepository.findByPrincipalName(String.valueOf(MEMBER_ID))).willReturn(Map.of(
                    CURRENT_SESSION, mock(Session.class),
                    "other-1", mock(Session.class),
                    "other-2", mock(Session.class)));
        }

        @Test
        @DisplayName("should save a bcrypt hash of the new password when current password matches")
        void savesNewPasswordHash() {
            memberService.changePassword(MEMBER_ID, new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD),
                    CURRENT_SESSION);

            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            verify(memberMapper).updatePasswordHash(eq(MEMBER_ID), hash.capture());
            assertThat(new BCryptPasswordEncoder().matches(NEW_PASSWORD, hash.getValue())).isTrue();
        }

        @Test
        @DisplayName("should delete the member's other sessions and keep the current one")
        void deletesOtherSessions() {
            memberService.changePassword(MEMBER_ID, new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD),
                    CURRENT_SESSION);

            ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
            verify(sessionRepository, times(2)).deleteById(deleted.capture());
            assertThat(deleted.getAllValues()).containsExactlyInAnyOrder("other-1", "other-2");
        }

        @Test
        @DisplayName("should throw MEMBER_PASSWORD_MISMATCH when current password is wrong")
        void rejectsWrongCurrentPassword() {
            assertThatThrownBy(() -> memberService.changePassword(MEMBER_ID,
                    new ChangePasswordRequest("wrong1234", NEW_PASSWORD), CURRENT_SESSION))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_PASSWORD_MISMATCH);
        }

        @Test
        @DisplayName("should not delete any session when current password is wrong")
        void keepsSessionsWhenRejected() {
            catchThrowable(() -> memberService.changePassword(MEMBER_ID,
                    new ChangePasswordRequest("wrong1234", NEW_PASSWORD), CURRENT_SESSION));

            verify(sessionRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("should not save a new password hash when current password is wrong")
        void keepsHashWhenRejected() {
            catchThrowable(() -> memberService.changePassword(MEMBER_ID,
                    new ChangePasswordRequest("wrong1234", NEW_PASSWORD), CURRENT_SESSION));

            verify(memberMapper, never()).updatePasswordHash(anyLong(), anyString());
        }
    }
}
