package com.livestream.channel;

import com.livestream.member.LoginMember;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChannelController {

    ChannelController(ChannelService channelService) {
    }

    @GetMapping("/api/channels/{channelId}")
    ChannelResponse channel(@PathVariable long channelId) {
        throw new UnsupportedOperationException("not implemented");
    }

    @PutMapping("/api/channels/{channelId}")
    ChannelResponse update(@PathVariable long channelId, @AuthenticationPrincipal LoginMember loginMember,
                           @Valid @RequestBody UpdateChannelRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    @GetMapping("/api/studio/channel")
    ChannelResponse myChannel(@AuthenticationPrincipal LoginMember loginMember) {
        throw new UnsupportedOperationException("not implemented");
    }
}
