package com.livestream.channel;

// 스튜디오 조회 응답. keyPrefix는 키의 앞 4자리, 키가 없으면 null
public record StreamKeyResponse(String keyPrefix, String obsServer) {
}
