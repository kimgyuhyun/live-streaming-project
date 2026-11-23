package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MediaMtxAuthRequestTest {

    @Nested
    @DisplayName("MediaMtxAuthRequest.toString")
    class ToString {

        // 레코드 기본 toString은 모든 필드를 찍는다. 예외 메시지·로그로 내부 비밀번호가 새기 쉽다(AC 6)
        @Test
        @DisplayName("should not include the password when the request is turned into text")
        void leavesOutPassword() {
            MediaMtxAuthRequest request = new MediaMtxAuthRequest("api", "", "", "spring", "internal-Pw_123");

            assertThat(request.toString()).doesNotContain("internal-Pw_123");
        }
    }
}
