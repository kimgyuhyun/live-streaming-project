package com.livestream.follow;

// 내가 이 채널을 팔로우했는지. 채널 조회 응답(nginx 1초 캐시)에 넣지 않고 따로 묻는다(PRD viewer 작은 결정)
public record FollowStatusResponse(boolean following) {
}
