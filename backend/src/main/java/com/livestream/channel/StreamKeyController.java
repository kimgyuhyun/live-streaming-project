package com.livestream.channel;

import com.livestream.member.LoginMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 스트리머만 부른다(SecurityConfig의 /api/studio/**). 발급과 재발급은 같은 요청이다
@RestController
class StreamKeyController {

    private final StreamKeyService streamKeyService;

    StreamKeyController(StreamKeyService streamKeyService) {
        this.streamKeyService = streamKeyService;
    }

    @PostMapping("/api/studio/stream-key")
    IssuedStreamKeyResponse issue(@AuthenticationPrincipal LoginMember loginMember) {
        return streamKeyService.issue(loginMember.id());
    }

    @GetMapping("/api/studio/stream-key")
    StreamKeyResponse find(@AuthenticationPrincipal LoginMember loginMember) {
        return streamKeyService.find(loginMember.id());
    }
}
