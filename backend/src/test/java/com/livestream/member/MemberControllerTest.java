package com.livestream.member;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 보안 필터(출처 검증)는 OriginValidationFilterTest·MemberSignUpIntegrationTest에서 본다.
@WebMvcTest(MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

    private static final String EMAIL = "viewer@example.com";
    private static final String PASSWORD = "abcd1234";
    private static final String NICKNAME = "시청자1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberService memberService;

    @BeforeEach
    void setUp() {
        given(memberService.signUp(any(SignUpRequest.class)))
                .willAnswer(invocation -> {
                    SignUpRequest request = invocation.getArgument(0);
                    return new MemberResponse(1L, request.email(), request.nickname(), Role.MEMBER);
                });
    }

    private ResultActions signUp(String email, String password, String nickname) throws Exception {
        return mockMvc.perform(post("/api/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignUpRequest(email, password, nickname))));
    }

    // @Email을 통과하면서(로컬 64자, 도메인 라벨 63자 이하) 전체 길이가 255자인 이메일
    private static String email255() {
        return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".com";
    }

    @Nested
    @DisplayName("POST /api/members")
    class SignUp {

        @Test
        @DisplayName("should return 201 with id, email, nickname and role when request is valid")
        void returnsCreatedMember() throws Exception {
            signUp(EMAIL, PASSWORD, NICKNAME)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.email").value(EMAIL))
                    .andExpect(jsonPath("$.nickname").value(NICKNAME))
                    .andExpect(jsonPath("$.role").value("MEMBER"));
        }

        @Test
        @DisplayName("should return 201 when password is 8 characters with letters and digits")
        void acceptsPasswordOf8() throws Exception {
            signUp(EMAIL, "abcd1234", NICKNAME).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 201 when password is 64 characters with letters and digits")
        void acceptsPasswordOf64() throws Exception {
            signUp(EMAIL, "a".repeat(32) + "1".repeat(32), NICKNAME).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 400 when password is 7 characters")
        void rejectsPasswordOf7() throws Exception {
            signUp(EMAIL, "abcd123", NICKNAME)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when password is 65 characters")
        void rejectsPasswordOf65() throws Exception {
            signUp(EMAIL, "a".repeat(32) + "1".repeat(33), NICKNAME)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 201 when nickname is 2 characters")
        void acceptsNicknameOf2() throws Exception {
            signUp(EMAIL, PASSWORD, "가나").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 201 when nickname is 12 characters")
        void acceptsNicknameOf12() throws Exception {
            signUp(EMAIL, PASSWORD, "가나다라마바사아자차카타").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 400 when nickname is 1 character")
        void rejectsNicknameOf1() throws Exception {
            signUp(EMAIL, PASSWORD, "가")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when nickname is 13 characters")
        void rejectsNicknameOf13() throws Exception {
            signUp(EMAIL, PASSWORD, "가나다라마바사아자차카타파")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when email is longer than 254 characters")
        void rejectsEmailOver254() throws Exception {
            signUp(email255(), PASSWORD, NICKNAME)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when password has letters only")
        void rejectsPasswordWithLettersOnly() throws Exception {
            signUp(EMAIL, "abcdefgh", NICKNAME)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when password has digits only")
        void rejectsPasswordWithDigitsOnly() throws Exception {
            signUp(EMAIL, "12345678", NICKNAME)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when nickname contains characters other than korean, english letters and digits")
        void rejectsNicknameWithSymbol() throws Exception {
            signUp(EMAIL, PASSWORD, "시청자!")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when nickname contains a standalone korean consonant")
        void rejectsNicknameWithConsonant() throws Exception {
            signUp(EMAIL, PASSWORD, "ㄱ나다")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return 400 when email format is invalid")
        void rejectsInvalidEmail() throws Exception {
            signUp("not-an-email", PASSWORD, NICKNAME)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("should return error body with code INVALID_INPUT and field message when validation fails")
        void returnsInvalidInputBody() throws Exception {
            signUp(EMAIL, "abcd123", NICKNAME)
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("비밀번호는 8~64자이고 영문과 숫자를 모두 포함해야 합니다"));
        }

        @Test
        @DisplayName("should return 409 with code MEMBER_EMAIL_DUPLICATED when email is taken")
        void returnsConflictForEmail() throws Exception {
            given(memberService.signUp(any(SignUpRequest.class)))
                    .willThrow(new BusinessException(ErrorCode.MEMBER_EMAIL_DUPLICATED));

            signUp(EMAIL, PASSWORD, NICKNAME)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MEMBER_EMAIL_DUPLICATED"));
        }

        @Test
        @DisplayName("should return 409 with code MEMBER_NICKNAME_DUPLICATED when nickname is taken")
        void returnsConflictForNickname() throws Exception {
            given(memberService.signUp(any(SignUpRequest.class)))
                    .willThrow(new BusinessException(ErrorCode.MEMBER_NICKNAME_DUPLICATED));

            signUp(EMAIL, PASSWORD, NICKNAME)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MEMBER_NICKNAME_DUPLICATED"));
        }
    }
}
