package com.livestream.channel;

import com.livestream.member.LoginMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StreamKeyController {

    StreamKeyController(StreamKeyService streamKeyService) {
    }

    @PostMapping("/api/studio/stream-key")
    IssuedStreamKeyResponse issue(@AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }

    @GetMapping("/api/studio/stream-key")
    StreamKeyResponse find(@AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }
}
