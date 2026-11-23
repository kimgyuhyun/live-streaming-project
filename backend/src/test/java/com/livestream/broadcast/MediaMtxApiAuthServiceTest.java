package com.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MediaMtxApiAuthServiceTest {

    private static final String INTERNAL_PASSWORD = "internal-Pw_123";

    private final MediaMtxApiAuthService service = new MediaMtxApiAuthService(INTERNAL_PASSWORD);

    // MediaMTX 1.21이 API 요청의 Basic 인증 값을 user·password로 싣는다. path·query는 빈 문자열이다
    private static MediaMtxAuthRequest api(String user, String password) {
        return new MediaMtxAuthRequest("api", "", "", user, password);
    }

    @Nested
    @DisplayName("MediaMtxApiAuthService.canCallApi")
    class CanCallApi {

        @Test
        @DisplayName("should allow when the action is api and the user and password are the internal ones")
        void allowsInternalCredentials() {
            assertThat(service.canCallApi(api(MediaMtxApiAuthService.INTERNAL_USER, INTERNAL_PASSWORD))).isTrue();
        }

        @Test
        @DisplayName("should deny when the password is the internal password followed by one more character")
        void deniesLongerPassword() {
            assertThat(service.canCallApi(api(MediaMtxApiAuthService.INTERNAL_USER, INTERNAL_PASSWORD + "x"))).isFalse();
        }

        // 설정이 빠진 서버에서 빈 값끼리 맞아 API가 자격 없이 열리면 안 된다. 요청에도 설정과 같은 값을 싣는다
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        @DisplayName("should deny even an empty password when no internal password is set")
        void deniesWhenNoInternalPassword(String internalPassword) {
            MediaMtxApiAuthService unset = new MediaMtxApiAuthService(internalPassword);

            assertThat(unset.canCallApi(api(MediaMtxApiAuthService.INTERNAL_USER, internalPassword))).isFalse();
        }

        @Test
        @DisplayName("should deny when the password differs from the internal password")
        void deniesDifferentPassword() {
            assertThat(service.canCallApi(api(MediaMtxApiAuthService.INTERNAL_USER, "wrong-password"))).isFalse();
        }

        // 자격 없이 부르면 MediaMTX는 빈 문자열을 싣는다
        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should deny when the password is empty")
        void deniesEmptyPassword(String password) {
            assertThat(service.canCallApi(api(MediaMtxApiAuthService.INTERNAL_USER, password))).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"admin", "Spring"})
        @DisplayName("should deny when the user is not the internal user")
        void deniesOtherUser(String user) {
            assertThat(service.canCallApi(api(user, INTERNAL_PASSWORD))).isFalse();
        }

        // 엔드포인트를 송출 확인과 같이 쓴다. 내부 자격이 송출 허가로 새면 안 된다
        @ParameterizedTest
        @ValueSource(strings = {"publish", "read", ""})
        @DisplayName("should deny a check whose action is not api even with the internal user and password")
        void deniesOtherActions(String action) {
            MediaMtxAuthRequest request = new MediaMtxAuthRequest(
                    action, "live/7", "", MediaMtxApiAuthService.INTERNAL_USER, INTERNAL_PASSWORD);

            assertThat(service.canCallApi(request)).isFalse();
        }
    }
}
