package com.livestream.common.error;

// 잠시 뒤에 다시 하라는 예외. 남은 시간을 함께 든다
public class RetryLaterException extends BusinessException {

    public RetryLaterException(ErrorCode code, long retryAfterSeconds) {
        super(code);
        throw new UnsupportedOperationException("not implemented");
    }

    public long getRetryAfterSeconds() {
        throw new UnsupportedOperationException("not implemented");
    }
}
