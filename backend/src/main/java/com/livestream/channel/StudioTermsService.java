package com.livestream.channel;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// 스튜디오 약관 동의(#35, PRD channel 작은 결정). StudioTerms의 항목은 모두 필수다
@Service
public class StudioTermsService {

    private final StudioTermsMapper studioTermsMapper;

    public StudioTermsService(StudioTermsMapper studioTermsMapper) {
        this.studioTermsMapper = studioTermsMapper;
    }

    // 지금 버전의 필수 약관에 모두 동의했으면 true. 예전 버전에만 동의했으면 false
    public boolean hasAgreed(long memberId) {
        List<StudioTermsAgreement> agreements = studioTermsMapper.findByMember(memberId);
        return EnumSet.allOf(StudioTerms.class).stream()
                .allMatch(terms -> agreements.contains(new StudioTermsAgreement(terms.name(), terms.version())));
    }

    // 필수 약관이 하나라도 빠지면 STUDIO_TERMS_REQUIRED(아무것도 저장하지 않는다). 이미 동의한 항목은 그대로 둔다.
    // 항목마다 따로 저장한다. 중간에 실패해도 빠진 항목이 있으면 동의하지 않은 것으로 보이고, 다시 동의하면 채워진다
    public void agree(long memberId, Collection<StudioTerms> terms) {
        if (!terms.containsAll(EnumSet.allOf(StudioTerms.class))) {
            throw new BusinessException(ErrorCode.STUDIO_TERMS_REQUIRED);
        }
        for (StudioTerms each : StudioTerms.values()) {
            try {
                studioTermsMapper.insertIfAbsent(memberId, each.name(), each.version());
            } catch (DuplicateKeyException alreadyAgreed) {
                // 같은 동의가 동시에 들어와 다른 요청이 먼저 저장했다. 결과는 같다
            }
        }
    }
}
