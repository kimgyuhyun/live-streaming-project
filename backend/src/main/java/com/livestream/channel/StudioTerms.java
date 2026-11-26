package com.livestream.channel;

// 지금 버전의 스튜디오 약관. 둘 다 필수. 약관을 바꾸면 version만 올린다(프론트 studioTermsText.ts의 버전과 같이)
public enum StudioTerms {

    STUDIO_TERMS(1),
    CONTENT_GUIDELINE(1);

    private final int version;

    StudioTerms(int version) {
        this.version = version;
    }

    public int version() {
        return version;
    }
}
