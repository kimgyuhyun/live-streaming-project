package com.livestream.member;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// 규칙은 가입(SignUpRequest)과 같다
public record ChangeNicknameRequest(
        @NotNull @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,12}$",
                message = "닉네임은 2~12자의 한글·영문·숫자만 쓸 수 있습니다") String nickname) {
}
