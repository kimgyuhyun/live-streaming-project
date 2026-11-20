package com.livestream.member;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
class MemberController {

    private final MemberService memberService;

    MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping
    ResponseEntity<MemberResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memberService.signUp(request));
    }

    @GetMapping("/me")
    MemberResponse me(@AuthenticationPrincipal LoginMember loginMember) {
        return memberService.findMe(loginMember.id());
    }

    @PutMapping("/me/nickname")
    MemberResponse changeNickname(@AuthenticationPrincipal LoginMember loginMember,
                                  @Valid @RequestBody ChangeNicknameRequest request) {
        return memberService.changeNickname(loginMember.id(), request);
    }

    // 지금 세션 ID를 넘겨 이 기기만 로그인을 유지한다. 로그인한 요청이라 세션이 늘 있다
    // IP는 시도 제한에 쓴다. nginx가 넘긴 실제 IP를 Tomcat이 옮겨 둔다(AuthController와 같다)
    @PutMapping("/me/password")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal LoginMember loginMember,
                                        @Valid @RequestBody ChangePasswordRequest request,
                                        HttpServletRequest httpRequest) {
        memberService.changePassword(loginMember.id(), request, httpRequest.getSession(false).getId(),
                httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
