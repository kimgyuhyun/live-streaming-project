package com.livestream.channel;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 스튜디오 약관 동의(#35): 동의 전에는 스튜디오 API를 막는다. 동의 여부 조회·동의 경로만 연다
@Configuration
class StudioTermsWebConfig implements WebMvcConfigurer {

    // 서비스는 요청이 올 때 꺼낸다. @WebMvcTest 슬라이스(컨트롤러 하나만 띄우는 테스트)에는 이 서비스가 없을 수 있다
    private final ObjectProvider<StudioTermsService> studioTermsService;

    StudioTermsWebConfig(ObjectProvider<StudioTermsService> studioTermsService) {
        this.studioTermsService = studioTermsService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new StudioTermsInterceptor(studioTermsService::getObject))
                .addPathPatterns("/api/studio/**")
                .excludePathPatterns("/api/studio/terms", "/api/studio/terms-agreement");
    }
}
