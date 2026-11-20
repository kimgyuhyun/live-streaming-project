package com.livestream.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// SecurityFilterChain(가입 경로 허용, 출처 검증 필터 등록)은 Green에서 만든다.
// PasswordEncoder 빈은 MemberService 생성자가 요구해서 앱이 뜨려면 지금 있어야 한다.
@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
