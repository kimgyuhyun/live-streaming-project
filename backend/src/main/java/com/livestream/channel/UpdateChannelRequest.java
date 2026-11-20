package com.livestream.channel;

// 확정 사양 3절: 채널 이름 2~20자, 소개 0~200자
public record UpdateChannelRequest(String name, String description) {
}
