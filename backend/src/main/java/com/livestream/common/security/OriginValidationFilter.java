package com.livestream.common.security;

import com.livestream.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

// 상태를 바꾸는 요청은 Origin(없으면 Referer)이 허용 목록에 있을 때만 통과시킨다.
// 브라우저는 상태 변경 요청에 Origin을 붙이므로, 출처를 알 수 없는 요청은 통과시킬 근거가 없다.
class OriginValidationFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final List<String> allowedOrigins;

    OriginValidationFilter(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String source = sourceOf(request);
        if (SAFE_METHODS.contains(request.getMethod()) || (source != null && allowedOrigins.contains(source))) {
            filterChain.doFilter(request, response);
            return;
        }
        ErrorCode code = ErrorCode.FORBIDDEN_ORIGIN;
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code.name() + "\",\"message\":\"" + code.getMessage() + "\"}");
    }

    // Origin이 있으면 그대로, 없으면 Referer에서 "scheme://host[:port]"만 떼어 낸다
    private static String sourceOf(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return origin;
        }
        String referer = request.getHeader("Referer");
        if (referer == null) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
