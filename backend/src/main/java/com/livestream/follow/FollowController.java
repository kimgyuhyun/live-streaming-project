package com.livestream.follow;

import com.livestream.member.LoginMember;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FollowController {

    private final FollowService followService;

    FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PutMapping("/api/channels/{channelId}/follow")
    ResponseEntity<Void> follow(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }

    @DeleteMapping("/api/channels/{channelId}/follow")
    ResponseEntity<Void> unfollow(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }

    @GetMapping("/api/channels/{channelId}/follow")
    FollowStatusResponse status(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }
}
