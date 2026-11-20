package com.livestream.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginValidationFilterTest {

    private static final String ALLOWED = "http://localhost:8080";

    private OriginValidationFilter filter;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new OriginValidationFilter(List.of(ALLOWED));
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    private MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/api/members");
    }

    // 필터가 요청을 다음으로 넘겼으면 체인이 그 요청을 받는다
    private boolean passed() {
        return chain.getRequest() != null;
    }

    @Nested
    @DisplayName("OriginValidationFilter")
    class DoFilter {

        @Test
        @DisplayName("should pass when origin is in allowed list")
        void passesAllowedOrigin() throws Exception {
            MockHttpServletRequest request = post();
            request.addHeader("Origin", ALLOWED);

            filter.doFilter(request, response, chain);

            assertThat(passed()).isTrue();
        }

        @Test
        @DisplayName("should pass when origin is missing and referer is in allowed list")
        void passesAllowedReferer() throws Exception {
            MockHttpServletRequest request = post();
            request.addHeader("Referer", ALLOWED + "/signup");

            filter.doFilter(request, response, chain);

            assertThat(passed()).isTrue();
        }

        @Test
        @DisplayName("should pass when method is GET without origin and referer")
        void passesGetWithoutOrigin() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/members");

            filter.doFilter(request, response, chain);

            assertThat(passed()).isTrue();
        }

        @Test
        @DisplayName("should return 403 with code FORBIDDEN_ORIGIN when origin is not allowed")
        void rejectsOtherOrigin() throws Exception {
            MockHttpServletRequest request = post();
            request.addHeader("Origin", "http://evil.example");

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN_ORIGIN\"");
        }

        @Test
        @DisplayName("should return 403 when post request has neither origin nor referer")
        void rejectsMissingOriginAndReferer() throws Exception {
            filter.doFilter(post(), response, chain);

            assertThat(response.getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("should return 403 when referer is not in allowed list")
        void rejectsOtherReferer() throws Exception {
            MockHttpServletRequest request = post();
            request.addHeader("Referer", "http://evil.example/page");

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(403);
        }
    }
}
