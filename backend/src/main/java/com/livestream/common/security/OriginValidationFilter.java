package com.livestream.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

// 상태를 바꾸는 요청은 Origin(없으면 Referer)이 허용 목록에 있을 때만 통과시킨다
class OriginValidationFilter extends OncePerRequestFilter {

    private final List<String> allowedOrigins;

    OriginValidationFilter(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) {
        throw new UnsupportedOperationException("not implemented");
    }
}
