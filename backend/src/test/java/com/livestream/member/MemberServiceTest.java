package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

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
}
