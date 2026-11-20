package com.livestream.common.error;

public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        super(code.getMessage());
        this.code = code;
    }

    // 기본 메시지에 값을 채워 보낼 때
    protected BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
