package com.livestream.channel;

import com.livestream.member.LoginMember;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/streamer-applications")
class StreamerApplicationController {

    private final StreamerApplicationService streamerApplicationService;

    StreamerApplicationController(StreamerApplicationService streamerApplicationService) {
        this.streamerApplicationService = streamerApplicationService;
    }

    @PostMapping
    ResponseEntity<StreamerApplicationResponse> apply(@AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }

    @GetMapping("/me")
    StreamerApplicationResponse mine(@AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }
}
