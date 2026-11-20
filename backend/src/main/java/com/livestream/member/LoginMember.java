package com.livestream.member;

import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

// 세션에 싣는 사람 정보. getName()이 세션 색인 키가 된다
public record LoginMember(long id, Role role) implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
