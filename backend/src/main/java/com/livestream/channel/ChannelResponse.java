package com.livestream.channel;

// live는 #11 전까지 늘 false. description은 저장값이 NULL(빈 소개)이면 ""
public record ChannelResponse(long id, String name, String description, boolean live) {
}
