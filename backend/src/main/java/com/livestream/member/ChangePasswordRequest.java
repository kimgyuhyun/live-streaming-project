package com.livestream.member;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 현재 비밀번호는 로그인과 같은 제한, 새 비밀번호는 가입과 같은 규칙.
// 넘는 값이 오면 BCrypt 한도 72바이트에 걸려 500이 나므로 비교 전에 400으로 막는다
public record ChangePasswordRequest(
        @NotBlank(message = "현재 비밀번호를 입력해 주세요")
        @Size(max = 64, message = "비밀번호는 64자 이하입니다")
        @Pattern(regexp = "^[!-~]*$", message = "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다") String currentPassword,
        @NotNull @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "비밀번호는 8~64자이고 영문과 숫자를 모두 포함해야 합니다")
        @Pattern(regexp = "^[!-~]*$", message = "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다") String newPassword) {
}
