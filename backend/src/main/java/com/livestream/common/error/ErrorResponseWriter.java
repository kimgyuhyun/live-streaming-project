package com.livestream.common.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

// 컨트롤러 밖(필터·보안 진입점)에서 에러 본문을 쓴다
public final class ErrorResponseWriter {

    private ErrorResponseWriter() {
    }

    public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        throw new UnsupportedOperationException("not implemented");
    }
}
