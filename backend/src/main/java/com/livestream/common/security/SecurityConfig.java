package com.livestream.common.security;

import com.livestream.common.error.ErrorCode;
import com.livestream.common.error.ErrorResponseWriter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
class SecurityConfig {

    // 위조 요청은 CSRF 토큰 대신 출처 검사(OriginValidationFilter)로 막는다 — PRD member "위조 요청 방어"
    // 로그인은 AuthController가 한다(PRD M1). 여기서는 경로별 권한·로그아웃·인증 실패 응답만 정한다
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Value("${app.allowed-origins}") List<String> allowedOrigins,
                                    SecurityContextRepository securityContextRepository) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // 401마다 요청을 세션에 저장해 두면 비로그인 요청마다 Redis에 빈 세션이 생긴다
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // POST만 받는다. GET이면 출처 검사를 건너뛰어 다른 사이트가 로그아웃시킬 수 있다
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                (request, response, e) -> ErrorResponseWriter.write(response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler(
                                (request, response, e) -> ErrorResponseWriter.write(response, ErrorCode.FORBIDDEN)))
                .addFilterAfter(new OriginValidationFilter(allowedOrigins), HeaderWriterFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/members").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/actuator/health").permitAll()
                        // MediaMTX의 송출 키 확인. 도커 내부망에서만 오고 nginx는 넘기지 않는다(PRD channel)
                        .requestMatchers("/internal/**").permitAll()
                        // 로그인할 때 ROLE_{역할} 권한을 싣는다(AuthController)
                        .requestMatchers("/api/operator/**").hasRole("OPERATOR")
                        // 채널 페이지는 비로그인 시청자도 본다. 고치기(PUT)는 로그인 + 주인 확인(ChannelService)
                        .requestMatchers(HttpMethod.GET, "/api/channels/*").permitAll()
                        .requestMatchers("/api/studio/**").hasRole("STREAMER")
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
