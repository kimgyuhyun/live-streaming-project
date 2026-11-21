package com.livestream.broadcast;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// MediaMTX가 도커 내부망으로만 부른다. nginx는 /internal/을 넘기지 않는다(PRD channel 작은 결정).
// 로그인·출처 검사 없이 받는다(SecurityConfig, OriginValidationFilter). 2xx면 송출 허용, 그 밖은 거부
@RestController
class PublishAuthController {

    private final PublishAuthService publishAuthService;

    PublishAuthController(PublishAuthService publishAuthService) {
        this.publishAuthService = publishAuthService;
    }

    @PostMapping("/internal/mediamtx/auth")
    ResponseEntity<Void> authorize(@RequestBody MediaMtxAuthRequest request) {
        HttpStatus status = publishAuthService.canPublish(request) ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).build();
    }
}
