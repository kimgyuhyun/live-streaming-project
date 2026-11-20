package com.livestream.channel;

import com.livestream.member.LoginMember;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 채널 페이지는 누구나 본다. 고치기는 주인만(ChannelService), 스튜디오는 스트리머만(SecurityConfig)
@RestController
class ChannelController {

    private final ChannelService channelService;

    ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping("/api/channels/{channelId}")
    ChannelResponse channel(@PathVariable long channelId) {
        return channelService.find(channelId);
    }

    @PutMapping("/api/channels/{channelId}")
    ChannelResponse update(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember,
                           @Valid @RequestBody UpdateChannelRequest request) {
        return channelService.update(channelId, loginMember.id(), request);
    }

    @GetMapping("/api/studio/channel")
    ChannelResponse myChannel(@AuthenticationPrincipal LoginMember loginMember) {
        return channelService.findMine(loginMember.id());
    }
}
