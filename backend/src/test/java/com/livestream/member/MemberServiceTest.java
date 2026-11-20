package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class MemberServiceTest {

    private MemberMapper memberMapper;
    private MemberService memberService;

    @BeforeEach
    void setUp() {
        memberMapper = mock(MemberMapper.class);
        memberService = new MemberService(memberMapper, new BCryptPasswordEncoder());
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
            MemberResponse result = memberService.login(new LoginRequest(EMAIL, PASSWORD));

            assertThat(result).isEqualTo(new MemberResponse(1L, EMAIL, "시청자1", Role.MEMBER));
        }

        // 목은 소문자 이메일로 찾을 때만 회원을 돌려준다
        @Test
        @DisplayName("should find member by lower-cased email when email contains upper case letters")
        void findsMemberByLowerCasedEmail() {
            MemberResponse result = memberService.login(new LoginRequest("Viewer@Example.COM", PASSWORD));

            assertThat(result.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should throw MEMBER_LOGIN_FAILED when password does not match")
        void rejectsWrongPassword() {
            assertThatThrownBy(() -> memberService.login(new LoginRequest(EMAIL, "wrong1234")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_LOGIN_FAILED);
        }

        @Test
        @DisplayName("should throw MEMBER_LOGIN_FAILED when email does not exist")
        void rejectsUnknownEmail() {
            assertThatThrownBy(() -> memberService.login(new LoginRequest("nobody@example.com", PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.MEMBER_LOGIN_FAILED);
        }

        // 비교를 건너뛰면 응답이 빨라져 "이 이메일은 없다"가 응답 시간으로 드러난다
        @Test
        @DisplayName("should compare password against a dummy hash once when email does not exist")
        void comparesDummyHashOnceForUnknownEmail() {
            PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
            MemberService service = new MemberService(memberMapper, encoder);

            catchThrowable(() -> service.login(new LoginRequest("nobody@example.com", PASSWORD)));

            verify(encoder, times(1)).matches(eq(PASSWORD), anyString());
        }

        // 빈 문자열이나 형식이 틀린 해시면 BCrypt가 계산 없이 바로 false를 돌려줘 응답 시간 차이가 다시 생긴다
        @Test
        @DisplayName("should compare against a bcrypt hash when email does not exist")
        void comparesAgainstBcryptHashForUnknownEmail() {
            PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
            MemberService service = new MemberService(memberMapper, encoder);
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);

            catchThrowable(() -> service.login(new LoginRequest("nobody@example.com", PASSWORD)));

            verify(encoder).matches(eq(PASSWORD), hash.capture());
            assertThat(hash.getValue()).matches("\\$2[aby]?\\$\\d{2}\\$[./0-9A-Za-z]{53}");
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
}
