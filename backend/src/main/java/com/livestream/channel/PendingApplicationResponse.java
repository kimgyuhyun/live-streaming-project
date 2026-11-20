package com.livestream.channel;

import java.time.LocalDateTime;

// 운영자 대기 목록 한 줄. 신청 시각은 streamer_application.created_at
public record PendingApplicationResponse(long id, String nickname, String email, LocalDateTime appliedAt) {
}
