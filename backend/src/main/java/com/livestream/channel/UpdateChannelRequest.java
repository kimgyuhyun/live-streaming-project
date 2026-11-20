package com.livestream.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 확정 사양 3절: 채널 이름 2~20자, 소개 0~200자
public record UpdateChannelRequest(
        @NotBlank(message = "채널 이름을 입력해 주세요")
        @Size(min = 2, max = 20, message = "채널 이름은 2~20자입니다") String name,
        @NotNull(message = "소개를 보내 주세요")
        @Size(max = 200, message = "소개는 200자 이하입니다") String description) {
}
