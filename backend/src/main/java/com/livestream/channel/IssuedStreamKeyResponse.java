package com.livestream.channel;

// 발급·재발급 응답. 전체 키는 여기에만 있다(PRD channel C1). obsStreamKey는 OBS 스트림 키 칸에 넣을 "{채널ID}?key={키}"
public record IssuedStreamKeyResponse(String streamKey, String keyPrefix, String obsServer, String obsStreamKey) {
}
