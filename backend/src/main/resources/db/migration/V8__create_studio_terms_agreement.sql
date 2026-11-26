-- 스튜디오 약관 동의(#35, PRD channel 작은 결정). 약관 항목·버전마다 한 줄. 약관을 바꾸면 버전을 올려 다시 동의받는다
CREATE TABLE studio_terms_agreement (
    member_id     NUMBER       NOT NULL,
    terms_code    VARCHAR2(30) NOT NULL,
    terms_version NUMBER(3)    NOT NULL,
    agreed_at     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    -- 같은 버전에 두 번(동시에) 동의해도 한 줄
    CONSTRAINT pk_studio_terms_agreement PRIMARY KEY (member_id, terms_code, terms_version),
    CONSTRAINT fk_studio_terms_agreement_member FOREIGN KEY (member_id) REFERENCES member (id)
);
