package com.livestream.follow;

// 팔로잉 목록 한 줄(#38). title은 방송 제목(정한 적 없으면 "{name}의 방송")
public record FollowingChannel(long channelId, String name, boolean live, String title) {
}
