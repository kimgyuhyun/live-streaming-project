package com.livestream.common.error;

// 모든 에러 응답 본문: {"code": "...", "message": "..."}
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.getMessage());
    }
}
