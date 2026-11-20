package com.livestream.member;

public record MemberResponse(long id, String email, String nickname, Role role) {
}
