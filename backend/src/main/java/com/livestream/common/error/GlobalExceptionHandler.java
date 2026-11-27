package com.livestream.common.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getCode();
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }

    // 남은 시간은 표준 Retry-After 헤더(초)로, 메시지에는 분으로 채운 문구를 보낸다
    @ExceptionHandler(RetryLaterException.class)
    ResponseEntity<ErrorResponse> handleRetryLater(RetryLaterException e) {
        ErrorCode code = e.getCode();
        return ResponseEntity.status(code.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(new ErrorResponse(code.name(), e.getMessage()));
    }

    // 메시지는 처음 걸린 필드의 메시지를 그대로 보낸다. 화면이 이 문장을 보여 준다
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(new ErrorResponse(ErrorCode.INVALID_INPUT.name(), message));
    }

    // 깨진 JSON. 파싱 오류 내용은 내부 구조를 드러내므로 기본 메시지만 보낸다
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus()).body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    // 숫자 자리에 글자가 온 경로 변수·쿼리 값(/api/channels/abc). 변환 오류 내용은 보내지 않는다
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus()).body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }
}
