package com.livestream.broadcast;

// MediaMTX가 보내는 확인 요청 JSON(authMethod: http). 송출(publish)은 path·query의 키로, API 호출(api)은 user·password로 판단한다.
// 그 밖의 필드(ip, token 등)는 받지 않는다
public record MediaMtxAuthRequest(String action, String path, String query, String user, String password) {
}
