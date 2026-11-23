package com.livestream.channel;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 운영자만 부른다(SecurityConfig의 /api/operator/**). 데모 준비 스크립트가 부른다(PRD channel C3)
@RestController
@RequestMapping("/api/operator/channels")
class ChannelOperatorController {

    private final ChannelService channelService;

    ChannelOperatorController(ChannelService channelService) {
        this.channelService = channelService;
    }

    // 다시 불러도 같은 결과(PUT). 준비 스크립트를 다시 돌려도 된다
    @PutMapping("/{channelId}/demo")
    ChannelResponse markDemo(@PathVariable long channelId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
