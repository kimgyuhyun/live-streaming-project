package com.livestream.channel;

// live는 진행 중 방송이 있으면 true(#11). description은 저장값이 NULL(빈 소개)이면 ""
public record ChannelResponse(long id, String name, String description, boolean live) {
}
