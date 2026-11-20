package com.livestream.member;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class MemberService {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberMapper memberMapper, PasswordEncoder passwordEncoder) {
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
    }

    // 이메일을 소문자로 맞추고 저장한다. 중복이면 BusinessException
    public MemberResponse signUp(SignUpRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
