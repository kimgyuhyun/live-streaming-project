package com.livestream.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class OperatorAccountInitializerTest {

    private static final String EMAIL = "operator@example.com";
    private static final String PASSWORD = "operator1234";

    private MemberMapper memberMapper;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        memberMapper = mock(MemberMapper.class);
        passwordEncoder = new BCryptPasswordEncoder();
    }

    private void runWith(String email, String password) {
        new OperatorAccountInitializer(memberMapper, passwordEncoder, email, password)
                .run(new DefaultApplicationArguments());
    }

    private Member insertedMember() {
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("run")
    class Run {

        @Test
        @DisplayName("should save an OPERATOR account with the hashed password when both values are set and the email has no account")
        void savesOperatorAccount() {
            given(memberMapper.existsByEmail(EMAIL)).willReturn(false);

            runWith(EMAIL, PASSWORD);

            Member saved = insertedMember();
            assertThat(saved.getRole()).isEqualTo(Role.OPERATOR);
            assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("should not save anything when an account with the email already exists")
        void skipsExistingAccount() {
            given(memberMapper.existsByEmail(EMAIL)).willReturn(true);

            runWith(EMAIL, PASSWORD);

            verify(memberMapper, never()).insert(any(Member.class));
        }

        @Test
        @DisplayName("should check and save the email in lowercase when the email has uppercase letters")
        void lowercasesEmail() {
            given(memberMapper.existsByEmail("operator@example.com")).willReturn(false);

            runWith("Operator@Example.COM", PASSWORD);

            assertThat(insertedMember().getEmail()).isEqualTo("operator@example.com");
        }

        @Test
        @DisplayName("should not save anything when the password is empty")
        void skipsWhenPasswordEmpty() {
            runWith(EMAIL, "");

            verify(memberMapper, never()).insert(any(Member.class));
        }

        @Test
        @DisplayName("should not save anything when the email is empty")
        void skipsWhenEmailEmpty() {
            runWith("", PASSWORD);

            verify(memberMapper, never()).insert(any(Member.class));
        }

        @Test
        @DisplayName("should not save anything when both values are empty")
        void skipsWhenBothEmpty() {
            runWith("", "");

            verify(memberMapper, never()).insert(any(Member.class));
        }

        @Test
        @DisplayName("should finish without exception when saving hits a duplicate and the email now has an account")
        void toleratesConcurrentCreation() {
            // 확인할 때는 없었는데, 저장하는 사이 다른 서버가 먼저 만들었다
            given(memberMapper.existsByEmail(EMAIL)).willReturn(false, true);
            willThrow(new DuplicateKeyException("UK_MEMBER_EMAIL")).given(memberMapper).insert(any(Member.class));

            assertThatCode(() -> runWith(EMAIL, PASSWORD)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should throw DuplicateKeyException when saving hits a duplicate and the email still has no account")
        void failsWhenNicknameTaken() {
            // 이메일은 여전히 없다 — 닉네임이 다른 계정과 겹쳤다
            given(memberMapper.existsByEmail(EMAIL)).willReturn(false, false);
            willThrow(new DuplicateKeyException("UK_MEMBER_NICKNAME")).given(memberMapper).insert(any(Member.class));

            assertThatThrownBy(() -> runWith(EMAIL, PASSWORD)).isInstanceOf(DuplicateKeyException.class);
        }
    }
}
