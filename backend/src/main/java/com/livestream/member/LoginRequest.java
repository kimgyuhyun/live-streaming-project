package com.livestream.member;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 비밀번호 길이·문자 제한은 가입 규칙과 같다. 넘는 값이 오면 BCrypt 한도 72바이트에 걸려 500이 난다
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요")
        @Size(max = 64, message = "비밀번호는 64자 이하입니다")
        @Pattern(regexp = "^[!-~]*$", message = "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다") String password) {
}
