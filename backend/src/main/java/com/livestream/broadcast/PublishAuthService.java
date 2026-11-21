package com.livestream.broadcast;

import com.livestream.channel.StreamKeyService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

// MediaMTX의 송출 키 확인(authMethod: http). 송출 주소는 rtmp://{서버}/live/{채널ID}?key={스트림키} (PRD channel)
@Service
public class PublishAuthService {

    // 18자리까지만 받는다. 더 길면 long을 넘는다
    private static final Pattern LIVE_PATH = Pattern.compile("live/(\\d{1,18})");
    private static final String KEY_PARAM = "key=";

    private final StreamKeyService streamKeyService;

    public PublishAuthService(StreamKeyService streamKeyService) {
        this.streamKeyService = streamKeyService;
    }

    // publish만 판단한다. read·api는 MediaMTX 설정(authHTTPExclude)에서 빼므로 여기 오면 설정이 빠진 것이라 막는다
    public boolean canPublish(MediaMtxAuthRequest request) {
        if (!"publish".equals(request.action()) || request.path() == null) {
            return false;
        }
        Matcher path = LIVE_PATH.matcher(request.path());
        if (!path.matches()) {
            return false;
        }
        String key = keyOf(request.query());
        return key != null && streamKeyService.matches(Long.parseLong(path.group(1)), key);
    }

    // query는 "a=1&key=..." 꼴. 키는 URL에 안전한 문자라 디코딩하지 않는다. 없거나 비었으면 null
    private static String keyOf(String query) {
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith(KEY_PARAM) && param.length() > KEY_PARAM.length()) {
                return param.substring(KEY_PARAM.length());
            }
        }
        return null;
    }
}
