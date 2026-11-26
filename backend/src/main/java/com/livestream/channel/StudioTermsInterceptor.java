package com.livestream.channel;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import com.livestream.member.LoginMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

// /api/studio/** 중 동의 여부 조회·동의 경로를 뺀 요청에서, 약관에 동의하지 않았으면 STUDIO_TERMS_NOT_AGREED.
// 보안 필터 뒤라 회원·운영자는 여기 오기 전에 403 FORBIDDEN이다. 던진 예외는 GlobalExceptionHandler가 응답으로 바꾼다.
// 등록은 StudioTermsWebConfig
class StudioTermsInterceptor implements HandlerInterceptor {

    private final Supplier<StudioTermsService> studioTermsService;

    StudioTermsInterceptor(Supplier<StudioTermsService> studioTermsService) {
        this.studioTermsService = studioTermsService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginMember member
                && !studioTermsService.get().hasAgreed(member.id())) {
            throw new BusinessException(ErrorCode.STUDIO_TERMS_NOT_AGREED);
        }
        return true;
    }
}
