package com.livestream.broadcast;

// MediaMTX가 보내는 확인 요청 JSON(authMethod: http). 송출(publish)은 path·query의 키로, API 호출(api)은 user·password로 판단한다.
// 그 밖의 필드(ip, token 등)는 받지 않는다
public record MediaMtxAuthRequest(String action, String path, String query, String user, String password) {

    // 비밀(password = 내부 비밀번호, query = 스트림 키)은 싣지 않는다. 예외 메시지·로그로 새지 않게(#26 AC 6, 확정 사양)
    @Override
    public String toString() {
        return "MediaMtxAuthRequest[action=" + action + ", path=" + path + ", user=" + user + "]";
    }
}
