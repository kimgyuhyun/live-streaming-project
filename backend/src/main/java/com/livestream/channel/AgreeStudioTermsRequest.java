package com.livestream.channel;

import jakarta.validation.constraints.NotNull;
import java.util.List;

// terms가 없으면 400 INVALID_INPUT. 모르는 코드는 역직렬화 실패로 400 INVALID_INPUT
public record AgreeStudioTermsRequest(@NotNull(message = "동의할 약관을 보내 주세요") List<StudioTerms> terms) {
}
