package com.livestream.member;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

public class OperatorAccountInitializer implements ApplicationRunner {

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

    @Override
    public void run(ApplicationArguments args) {
        throw new UnsupportedOperationException("not implemented");
    }
}
