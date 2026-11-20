package com.livestream.member;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final MemberService memberService;
    private final SecurityContextRepository securityContextRepository;

    AuthController(MemberService memberService, SecurityContextRepository securityContextRepository) {
        this.memberService = memberService;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    MemberResponse login(@Valid @RequestBody LoginRequest request,
                         HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        throw new UnsupportedOperationException("not implemented");
    }
}
