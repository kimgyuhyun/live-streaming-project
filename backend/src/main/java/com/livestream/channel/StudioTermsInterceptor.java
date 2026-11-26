package com.livestream.channel;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

// /api/studio/** 중 동의 여부 조회·동의 경로를 뺀 요청에서, 약관에 동의하지 않았으면 STUDIO_TERMS_NOT_AGREED.
// 보안 필터 뒤라 회원·운영자는 여기 오기 전에 403 FORBIDDEN이다. 등록은 StudioTermsWebConfig
class StudioTermsInterceptor implements HandlerInterceptor {

    private final StudioTermsService studioTermsService;

    StudioTermsInterceptor(StudioTermsService studioTermsService) {
        this.studioTermsService = studioTermsService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        throw new UnsupportedOperationException("not implemented");
    }
}
