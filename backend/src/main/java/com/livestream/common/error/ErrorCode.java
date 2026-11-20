package com.livestream.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // 메시지는 처음 걸린 필드의 메시지로 바꿔 보낸다
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    FORBIDDEN_ORIGIN(HttpStatus.FORBIDDEN, "허용되지 않은 출처의 요청입니다"),
    MEMBER_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다"),
    MEMBER_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
    MEMBER_NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다"),
    CHANNEL_APPLICATION_PENDING(HttpStatus.CONFLICT, "이미 대기 중인 스트리머 신청이 있습니다"),
    CHANNEL_APPLICATION_NOT_MEMBER(HttpStatus.CONFLICT, "회원만 스트리머 신청을 할 수 있습니다");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
