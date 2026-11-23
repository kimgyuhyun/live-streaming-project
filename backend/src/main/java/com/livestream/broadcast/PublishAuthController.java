package com.livestream.broadcast;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// MediaMTX가 도커 내부망으로만 부른다. nginx는 /internal/을 넘기지 않는다(PRD channel 작은 결정).
// 로그인·출처 검사 없이 받는다(SecurityConfig, OriginValidationFilter). 2xx면 허용, 그 밖은 거부.
// 송출(publish)은 키로, API 호출(api)은 내부 비밀번호로 판단한다(#26). authHTTPAddress가 하나라 엔드포인트도 하나다
@RestController
class PublishAuthController {

    private final PublishAuthService publishAuthService;
    private final MediaMtxApiAuthService apiAuthService;

    PublishAuthController(PublishAuthService publishAuthService, MediaMtxApiAuthService apiAuthService) {
        this.publishAuthService = publishAuthService;
        this.apiAuthService = apiAuthService;
    }

    @PostMapping("/internal/mediamtx/auth")
    ResponseEntity<Void> authorize(@RequestBody MediaMtxAuthRequest request) {
        boolean allowed = "api".equals(request.action())
                ? apiAuthService.canCallApi(request)
                : publishAuthService.canPublish(request);
        HttpStatus status = allowed ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).build();
    }
}
