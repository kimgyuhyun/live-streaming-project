package com.livestream.channel;

import com.livestream.member.LoginMember;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 스트리머만 부른다(SecurityConfig의 /api/studio/**). 이 두 경로는 동의 전에도 열려 있다(StudioTermsInterceptor)
@RestController
class StudioTermsController {

    private final StudioTermsService studioTermsService;

    StudioTermsController(StudioTermsService studioTermsService) {
        this.studioTermsService = studioTermsService;
    }

    @GetMapping("/api/studio/terms")
    StudioTermsResponse terms(@AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping("/api/studio/terms-agreement")
    ResponseEntity<Void> agree(@AuthenticationPrincipal LoginMember loginMember,
                               @Valid @RequestBody AgreeStudioTermsRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
