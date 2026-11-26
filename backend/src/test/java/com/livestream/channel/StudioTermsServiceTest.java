package com.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.livestream.common.error.BusinessException;
import com.livestream.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StudioTermsServiceTest {

    private static final long MEMBER_ID = 1L;

    // 테이블처럼 (회원, 코드, 버전)이 같은 줄은 하나만 둔다. 실제 SQL은 StudioTermsIntegrationTest가 본다
    private static final class FakeStudioTermsMapper implements StudioTermsMapper {

        private record Row(long memberId, String termsCode, int termsVersion) {
        }

        private final List<Row> rows = new ArrayList<>();

        @Override
        public List<StudioTermsAgreement> findByMember(long memberId) {
            return rows.stream()
                    .filter(row -> row.memberId() == memberId)
                    .map(row -> new StudioTermsAgreement(row.termsCode(), row.termsVersion()))
                    .toList();
        }

        @Override
        public void insertIfAbsent(long memberId, String termsCode, int termsVersion) {
            Row row = new Row(memberId, termsCode, termsVersion);
            if (!rows.contains(row)) {
                rows.add(row);
            }
        }
    }

    private FakeStudioTermsMapper mapper;
    private StudioTermsService service;

    @BeforeEach
    void setUp() {
        mapper = new FakeStudioTermsMapper();
        service = new StudioTermsService(mapper);
    }

    @Nested
    @DisplayName("StudioTermsService.hasAgreed")
    class HasAgreed {

        @Test
        @DisplayName("should return false when the member has agreed to nothing")
        void falseWhenNothing() {
            assertThat(service.hasAgreed(MEMBER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return true after the member agrees to both required terms")
        void trueAfterAgreeingBoth() {
            service.agree(MEMBER_ID, List.of(StudioTerms.STUDIO_TERMS, StudioTerms.CONTENT_GUIDELINE));

            assertThat(service.hasAgreed(MEMBER_ID)).isTrue();
        }

        @Test
        @DisplayName("should return false when the member agreed to only one of the two required terms")
        void falseWithOnlyOne() {
            mapper.insertIfAbsent(MEMBER_ID, "STUDIO_TERMS", StudioTerms.STUDIO_TERMS.version());

            assertThat(service.hasAgreed(MEMBER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return false when the member agreed to an older version of a required terms")
        void falseWithOlderVersion() {
            mapper.insertIfAbsent(MEMBER_ID, "STUDIO_TERMS", StudioTerms.STUDIO_TERMS.version());
            mapper.insertIfAbsent(MEMBER_ID, "CONTENT_GUIDELINE", StudioTerms.CONTENT_GUIDELINE.version() - 1);

            assertThat(service.hasAgreed(MEMBER_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("StudioTermsService.agree")
    class Agree {

        @Test
        @DisplayName("should record STUDIO_TERMS and CONTENT_GUIDELINE at their current versions when the member agrees to both")
        void recordsBoth() {
            service.agree(MEMBER_ID, List.of(StudioTerms.STUDIO_TERMS, StudioTerms.CONTENT_GUIDELINE));

            assertThat(mapper.findByMember(MEMBER_ID)).containsExactlyInAnyOrder(
                    new StudioTermsAgreement("STUDIO_TERMS", StudioTerms.STUDIO_TERMS.version()),
                    new StudioTermsAgreement("CONTENT_GUIDELINE", StudioTerms.CONTENT_GUIDELINE.version()));
        }

        @Test
        @DisplayName("should keep one record per terms when the member agrees twice")
        void keepsOneRecordWhenTwice() {
            List<StudioTerms> both = List.of(StudioTerms.STUDIO_TERMS, StudioTerms.CONTENT_GUIDELINE);
            service.agree(MEMBER_ID, both);

            service.agree(MEMBER_ID, both);

            assertThat(mapper.findByMember(MEMBER_ID)).hasSize(2);
        }

        @Test
        @DisplayName("should throw STUDIO_TERMS_REQUIRED without saving when CONTENT_GUIDELINE is missing")
        void throwsWhenContentGuidelineMissing() {
            assertThatThrownBy(() -> service.agree(MEMBER_ID, List.of(StudioTerms.STUDIO_TERMS)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.STUDIO_TERMS_REQUIRED);
        }

        @Test
        @DisplayName("should save nothing when CONTENT_GUIDELINE is missing")
        void savesNothingWhenContentGuidelineMissing() {
            try {
                service.agree(MEMBER_ID, List.of(StudioTerms.STUDIO_TERMS));
            } catch (BusinessException expected) {
                // 거절 자체는 위 테스트가 본다. 여기서는 저장 여부만 본다
            }

            assertThat(mapper.findByMember(MEMBER_ID)).isEmpty();
        }

        @Test
        @DisplayName("should throw STUDIO_TERMS_REQUIRED without saving when the terms list is empty")
        void throwsWhenEmpty() {
            assertThatThrownBy(() -> service.agree(MEMBER_ID, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.STUDIO_TERMS_REQUIRED);
        }
    }
}
