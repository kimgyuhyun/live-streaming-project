package com.livestream.broadcast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// MediaMTX API 호출 확인(action: api, #26). Spring만 내부 비밀번호를 알고 MediaMtxClient가 Basic 인증으로 싣는다
@Service
public class MediaMtxApiAuthService {

    static final String INTERNAL_USER = "spring";

    // 설정되지 않았으면(빈 값·공백) null — 어떤 API 호출도 허용하지 않는다. 빈 값끼리 맞아 API가 열리면 안 된다
    private final byte[] internalPassword;

    MediaMtxApiAuthService(@Value("${app.mediamtx.api-password:}") String internalPassword) {
        this.internalPassword = internalPassword == null || internalPassword.isBlank()
                ? null
                : internalPassword.getBytes(StandardCharsets.UTF_8);
    }

    // api만 판단한다. publish는 PublishAuthService가 키로 본다 — 내부 자격이 송출 허가가 되지 않게.
    // 비밀번호는 일정 시간 비교(PRD channel C1과 같은 방식)
    public boolean canCallApi(MediaMtxAuthRequest request) {
        if (internalPassword == null || !"api".equals(request.action())
                || !INTERNAL_USER.equals(request.user()) || request.password() == null) {
            return false;
        }
        return MessageDigest.isEqual(internalPassword, request.password().getBytes(StandardCharsets.UTF_8));
    }
}
