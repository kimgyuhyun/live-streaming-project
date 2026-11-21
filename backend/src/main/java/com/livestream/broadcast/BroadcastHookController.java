package com.livestream.broadcast;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// MediaMTX의 runOnAvailable·runOnUnavailable 훅이 wget으로 부른다(infra/mediamtx/mediamtx.yml).
// /internal/ 아래라 도커 내부망에서만 오고, 로그인·출처 검사 없이 받는다(#10). path는 MTX_PATH(live/21 꼴)
@RestController
class BroadcastHookController {

    private final BroadcastService broadcastService;

    BroadcastHookController(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @PostMapping("/internal/mediamtx/available")
    ResponseEntity<Void> available(@RequestParam String path) {
        broadcastService.onAvailable(path);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/mediamtx/unavailable")
    ResponseEntity<Void> unavailable(@RequestParam String path) {
        broadcastService.onUnavailable(path);
        return ResponseEntity.noContent().build();
    }
}
