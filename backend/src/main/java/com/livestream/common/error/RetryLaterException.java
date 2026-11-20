package com.livestream.common.error;

// 잠시 뒤에 다시 하라는 예외. 남은 시간을 함께 든다.
// 코드의 메시지에 있는 %d를 남은 분(올림)으로 채운다. 1초가 남아도 "1분 후"다
public class RetryLaterException extends BusinessException {

    private final long retryAfterSeconds;

    public RetryLaterException(ErrorCode code, long retryAfterSeconds) {
        super(code, String.format(code.getMessage(), (retryAfterSeconds + 59) / 60));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
