package com.livestream.broadcast;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BroadcastHookController {

    private final BroadcastService broadcastService;

    BroadcastHookController(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @PostMapping("/internal/mediamtx/available")
    ResponseEntity<Void> available(@RequestParam String path) {
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping("/internal/mediamtx/unavailable")
    ResponseEntity<Void> unavailable(@RequestParam String path) {
        throw new UnsupportedOperationException("not implemented");
    }
}
