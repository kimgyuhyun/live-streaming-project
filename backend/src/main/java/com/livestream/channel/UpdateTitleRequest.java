package com.livestream.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 확정 사양 4절: 방송 제목 1~50자. 채널 이름(UpdateChannelRequest)과 같은 방식 —
// 공백만 있는 제목은 @NotBlank가, 51자는 @Size가 막는다
public record UpdateTitleRequest(
        @NotBlank(message = "방송 제목을 입력해 주세요")
        @Size(max = 50, message = "방송 제목은 1~50자입니다") String title) {
}
