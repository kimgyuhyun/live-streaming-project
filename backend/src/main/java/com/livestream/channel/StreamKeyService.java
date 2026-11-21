package com.livestream.channel;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// 스트림 키는 해시와 앞 4자리만 저장하고, 전체 키는 발급 응답에서 한 번만 보여 준다(PRD channel C1)
@Service
public class StreamKeyService {

    private static final int KEY_BYTES = 32;
    private static final int PREFIX_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelMapper channelMapper;
    private final String obsServer;
    private final PublisherDisconnector publisherDisconnector;

    public StreamKeyService(ChannelMapper channelMapper, @Value("${app.obs-server}") String obsServer,
                            PublisherDisconnector publisherDisconnector) {
        this.channelMapper = channelMapper;
        this.obsServer = obsServer;
        this.publisherDisconnector = publisherDisconnector;
    }

    // 키가 있든 없든 새 키로 덮어쓴다(발급·재발급). 이전 송출 끊기는 #10
    public IssuedStreamKeyResponse issue(long memberId) {
        Channel channel = findChannel(memberId);
        String key = newKey();
        String prefix = key.substring(0, PREFIX_LENGTH);
        channelMapper.updateStreamKey(channel.getId(), sha256Hex(key), prefix);
        return new IssuedStreamKeyResponse(key, prefix, obsServer, channel.getId() + "?key=" + key);
    }

    public StreamKeyResponse find(long memberId) {
        return new StreamKeyResponse(findChannel(memberId).getStreamKeyPrefix(), obsServer);
    }

    public boolean matches(long channelId, String key) {
        throw new UnsupportedOperationException("not implemented");
    }

    private Channel findChannel(long memberId) {
        return channelMapper.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_NOT_FOUND));
    }

    // 보안 난수 32바이트를 URL에 안전한 문자로(패딩 없음, 43자). 송출 주소의 query에 그대로 들어간다
    private static String newKey() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 키 확인(#10)이 같은 형식으로 다시 계산해 비교한다
    private static String sha256Hex(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 모든 Java 구현은 SHA-256을 제공해야 한다(MessageDigest 명세)
            throw new IllegalStateException(e);
        }
    }
}
