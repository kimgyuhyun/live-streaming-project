package com.livestream.broadcast;

// MediaMTX가 송출 시작 때 보내는 JSON(authMethod: http). 그 밖의 필드(ip, user, password, token 등)는 받지 않는다
public record MediaMtxAuthRequest(String action, String path, String query) {
}
