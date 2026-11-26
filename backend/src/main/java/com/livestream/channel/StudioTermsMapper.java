package com.livestream.channel;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// SQL은 resources/mapper/channel/StudioTermsMapper.xml
@Mapper
public interface StudioTermsMapper {

    List<StudioTermsAgreement> findByMember(long memberId);

    // 같은 (회원, 코드, 버전)이 있으면 아무것도 하지 않는다
    void insertIfAbsent(@Param("memberId") long memberId, @Param("termsCode") String termsCode,
                        @Param("termsVersion") int termsVersion);
}
