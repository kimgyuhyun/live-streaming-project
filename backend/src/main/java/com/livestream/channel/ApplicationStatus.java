package com.livestream.channel;

// 스트리머 신청 상태. NONE은 "신청한 적 없음"을 응답에서만 나타낸다(DB에는 저장하지 않는다)
public enum ApplicationStatus {
    NONE,
    PENDING,
    APPROVED,
    REJECTED
}
