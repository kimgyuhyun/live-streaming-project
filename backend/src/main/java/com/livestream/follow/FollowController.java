package com.livestream.follow;

import com.livestream.member.LoginMember;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 팔로우는 회원만. 비로그인은 SecurityConfig의 anyRequest().authenticated()가 401로 막는다
// (GET /api/channels/*의 permitAll은 한 단계 경로만 열어 /follow에는 닿지 않는다). 둘 다 멱등이라 204
@RestController
class FollowController {

    private final FollowService followService;

    FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PutMapping("/api/channels/{channelId}/follow")
    ResponseEntity<Void> follow(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember) {
        followService.follow(loginMember.id(), channelId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/channels/{channelId}/follow")
    ResponseEntity<Void> unfollow(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember) {
        followService.unfollow(loginMember.id(), channelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/channels/{channelId}/follow")
    FollowStatusResponse status(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember) {
        return new FollowStatusResponse(followService.isFollowing(loginMember.id(), channelId));
    }

    // 내가 팔로우한 채널(#38). offset이 없으면 0
    @GetMapping("/api/me/following")
    FollowingResponse following(@RequestParam(defaultValue = "0") int offset,
                                @AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }
}
