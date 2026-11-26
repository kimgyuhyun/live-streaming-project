package com.livestream.channel;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

// 개발 편의상 운영자도 스튜디오를 쓴다. 채널은 스트리머 승인 때 생기므로 운영자에게는 앱이 뜰 때 만들어 준다.
// ApplicationReadyEvent는 OperatorAccountInitializer(ApplicationRunner)가 운영자 계정을 만든 뒤에 온다
@Component
class OperatorChannelInitializer {

    private final ChannelMapper channelMapper;

    OperatorChannelInitializer(ChannelMapper channelMapper) {
        this.channelMapper = channelMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    void createMissingChannels() {
        try {
            channelMapper.insertForOperatorsWithoutChannel();
        } catch (DuplicateKeyException alreadyCreated) {
            // 서버 두 대가 함께 떠서 다른 쪽이 먼저 만들었다(uk_channel_member). 결과는 같다
        }
    }
}
