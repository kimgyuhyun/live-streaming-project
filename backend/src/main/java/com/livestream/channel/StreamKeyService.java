package com.livestream.channel;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// 스트림 키는 해시와 앞 4자리만 저장하고, 전체 키는 발급 응답에서 한 번만 보여 준다(PRD channel C1)
@Service
public class StreamKeyService {

    private static final int KEY_BYTES = 32;
    private static final int PREFIX_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(StreamKeyService.class);

    private final ChannelMapper channelMapper;
    private final String obsServer;
    private final PublisherDisconnector publisherDisconnector;

    public StreamKeyService(ChannelMapper channelMapper, @Value("${app.obs-server}") String obsServer,
                            PublisherDisconnector publisherDisconnector) {
        this.channelMapper = channelMapper;
        this.obsServer = obsServer;
        this.publisherDisconnector = publisherDisconnector;
    }

    // 키가 있든 없든 새 키로 덮어쓴 뒤, 이전 키로 하던 송출을 끊는다(발급·재발급).
    // 순서가 중요하다: 끊고 나서 저장하면 OBS가 2초 만에 이전 키로 다시 붙는다(PRD channel 재발급 순서).
    // @Transactional을 걸지 않는다 — 매퍼의 UPDATE가 바로 커밋되어, 끊기는 항상 커밋 뒤에 불린다
    public IssuedStreamKeyResponse issue(long memberId) {
        Channel channel = findChannel(memberId);
        String key = newKey();
        String prefix = key.substring(0, PREFIX_LENGTH);
        channelMapper.updateStreamKey(channel.getId(), sha256Hex(key), prefix);
        disconnectPublisher(channel.getId());
        return new IssuedStreamKeyResponse(key, prefix, obsServer, channel.getId() + "?key=" + key);
    }

    public StreamKeyResponse find(long memberId) {
        return new StreamKeyResponse(findChannel(memberId).getStreamKeyPrefix(), obsServer);
    }

    // 송출 시작 때 MediaMTX가 넘긴 키 확인(broadcast). 어디서 달라지든 같은 시간이 걸리는 비교를 쓴다(PRD channel C1)
    public boolean matches(long channelId, String key) {
        String savedHash = channelMapper.findById(channelId).map(Channel::getStreamKeyHash).orElse(null);
        if (savedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(savedHash.getBytes(StandardCharsets.US_ASCII),
                sha256Hex(key).getBytes(StandardCharsets.US_ASCII));
    }

    // 끊기가 실패해도 재발급은 성공이다. 새 키는 이미 저장됐고, 이전 키로는 다시 붙지 못한다.
    // 예외 메시지는 남기지 않는다 — MediaMTX 연결 목록 응답에는 키가 들어 있어 파싱 오류에 본문 조각이 섞일 수 있다
    private void disconnectPublisher(long channelId) {
        try {
            publisherDisconnector.disconnect(channelId);
        } catch (RuntimeException e) {
            log.warn("재발급 뒤 송출 연결을 끊지 못했다 channelId={} cause={}", channelId, e.getClass().getSimpleName());
        }
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
