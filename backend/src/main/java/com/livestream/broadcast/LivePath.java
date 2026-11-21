package com.livestream.broadcast;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// MediaMTX 경로 live/{채널ID}를 읽는 규칙 한 곳. 키 확인(#10)과 방송 상태(#11)가 같이 쓴다.
// 둘이 경로를 다르게 읽으면 한쪽만 통과하는 구멍이 생긴다
final class LivePath {

    // 18자리까지만 받는다. 더 길면 long을 넘는다.
    // 0으로 시작하면 막는다 — live/007은 MediaMTX에서 live/7과 다른 경로라, 받아 주면 같은 채널로 두 번째 송출이 되고
    // 재발급 때 live/7만 끊으므로 그 송출이 살아남는다(#10 보안 점검)
    private static final Pattern LIVE_PATH = Pattern.compile("live/([1-9]\\d{0,17})");

    private LivePath() {
    }

    // live/{채널ID}이면 채널 id, 아니면 빈 값
    static OptionalLong channelIdOf(String path) {
        if (path == null) {
            return OptionalLong.empty();
        }
        Matcher matcher = LIVE_PATH.matcher(path);
        return matcher.matches() ? OptionalLong.of(Long.parseLong(matcher.group(1))) : OptionalLong.empty();
    }
}
