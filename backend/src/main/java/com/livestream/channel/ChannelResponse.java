package com.livestream.channel;

// live는 진행 중 방송이 있으면 true(#11). description은 저장값이 NULL(빈 소개)이면 "".
// title은 방송 제목, 정한 적 없으면 "{name}의 방송"(#13). demo는 운영자가 데모 표시를 붙였으면 true(#14)
public record ChannelResponse(long id, String name, String description, boolean live, String title, boolean demo) {
}
