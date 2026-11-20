package com.livestream.member;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// ApplicationRunner: Spring Boot가 앱 시작을 마친 직후 한 번 부른다
@Component
public class OperatorAccountInitializer implements ApplicationRunner {

    // 운영자는 한 명이고(PRD member M4) 닉네임으로 정해 둔 값이 없어 고정한다
    private static final String NICKNAME = "운영자";

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    OperatorAccountInitializer(MemberMapper memberMapper, PasswordEncoder passwordEncoder,
                               @Value("${app.operator.email:}") String email,
                               @Value("${app.operator.password:}") String password) {
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    // 두 값이 다 있고 그 이메일의 계정이 없을 때만 만든다. 이미 있으면 비밀번호도 역할도 바꾸지 않는다 (PRD member M4)
    // .env.example을 그대로 복사하면 값이 빈 문자열로 들어오므로 빈 값도 "없음"이다
    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        if (memberMapper.existsByEmail(normalizedEmail)) {
            return;
        }
        try {
            memberMapper.insert(new Member(normalizedEmail, passwordEncoder.encode(password), NICKNAME, Role.OPERATOR));
        } catch (DuplicateKeyException e) {
            // 서버 두 대가 함께 확인을 통과했으면 먼저 저장한 쪽이 이긴 것이라 끝낸다.
            // 이메일이 여전히 없으면 닉네임이 다른 계정과 겹친 것이다. 운영자 없이 뜨지 않게 그대로 던진다
            if (!memberMapper.existsByEmail(normalizedEmail)) {
                throw e;
            }
        }
    }
}
