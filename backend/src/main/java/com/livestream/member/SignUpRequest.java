package com.livestream.member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "비밀번호는 8~64자이고 영문과 숫자를 모두 포함해야 합니다")
        // 공백을 뺀 ASCII만. 여러 바이트 문자로 64자를 채우면 BCrypt 한도 72바이트를 넘는다
        @Pattern(regexp = "^[!-~]*$", message = "비밀번호는 영문·숫자·특수문자만 쓸 수 있습니다") String password,
        @NotNull @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,12}$",
                message = "닉네임은 2~12자의 한글·영문·숫자만 쓸 수 있습니다") String nickname) {
}
