package com.livestream.common.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.HeaderWriterFilter;

@Configuration
class SecurityConfig {

    // 위조 요청은 CSRF 토큰 대신 출처 검사(OriginValidationFilter)로 막는다 — PRD member "위조 요청 방어"
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Value("${app.allowed-origins}") List<String> allowedOrigins) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterAfter(new OriginValidationFilter(allowedOrigins), HeaderWriterFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/members").permitAll()
                        .requestMatchers("/api/actuator/health").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    // 로그인한 사람을 세션에 저장한다. AuthController가 로그인할 때 같은 인스턴스를 쓴다
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
