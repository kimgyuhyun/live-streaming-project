package com.livestream.channel;

import java.util.Collection;
import org.springframework.stereotype.Service;

// 스튜디오 약관 동의(#35, PRD channel 작은 결정)
@Service
public class StudioTermsService {

    private final StudioTermsMapper studioTermsMapper;

    public StudioTermsService(StudioTermsMapper studioTermsMapper) {
        this.studioTermsMapper = studioTermsMapper;
    }

    // 지금 버전의 필수 약관에 모두 동의했으면 true
    public boolean hasAgreed(long memberId) {
        throw new UnsupportedOperationException("not implemented");
    }

    // 필수 약관이 하나라도 빠지면 STUDIO_TERMS_REQUIRED(아무것도 저장하지 않는다). 이미 동의한 항목은 그대로 둔다
    public void agree(long memberId, Collection<StudioTerms> terms) {
        throw new UnsupportedOperationException("not implemented");
    }
}
