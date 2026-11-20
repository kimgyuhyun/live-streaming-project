package com.livestream.member;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 로그아웃은 SecurityConfig의 로그아웃 필터가 처리한다
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final MemberService memberService;
    private final SecurityContextRepository securityContextRepository;

    AuthController(MemberService memberService, SecurityContextRepository securityContextRepository) {
        this.memberService = memberService;
        this.securityContextRepository = securityContextRepository;
    }

    // 로그인 전부터 있던 세션은 ID를 바꾼다. 공격자가 미리 받아 둔 세션 ID로 로그인되는 것(세션 고정)을 막는다
    @PostMapping("/login")
    MemberResponse login(@Valid @RequestBody LoginRequest request,
                         HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 실제 사용자 IP. nginx가 넘긴 X-Forwarded-For를 Tomcat이 옮겨 둔다(forward-headers-strategy: native)
        MemberResponse member = memberService.login(request, httpRequest.getRemoteAddr());
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        LoginMember principal = new LoginMember(member.id(), member.role());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + member.role().name()))));
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        return member;
    }
}
