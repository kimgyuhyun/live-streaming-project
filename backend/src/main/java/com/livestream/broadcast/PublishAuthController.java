package com.livestream.broadcast;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PublishAuthController {

    private final PublishAuthService publishAuthService;

    PublishAuthController(PublishAuthService publishAuthService) {
        this.publishAuthService = publishAuthService;
    }

    @PostMapping("/internal/mediamtx/auth")
    ResponseEntity<Void> authorize(@RequestBody MediaMtxAuthRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
