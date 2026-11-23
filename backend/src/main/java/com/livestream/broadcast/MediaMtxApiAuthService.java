package com.livestream.broadcast;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// MediaMTX API 호출 확인(action: api, #26). Spring만 내부 비밀번호를 알고 MediaMtxClient가 Basic 인증으로 싣는다
@Service
public class MediaMtxApiAuthService {

    static final String INTERNAL_USER = "spring";

    MediaMtxApiAuthService(@Value("${app.mediamtx.api-password:}") String internalPassword) {
    }

    public boolean canCallApi(MediaMtxAuthRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
