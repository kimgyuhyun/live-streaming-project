-- 스트리머 한 명당 채널 하나(확정 사양 3절). 승인할 때 만든다(#7)
CREATE TABLE channel (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id   NUMBER            NOT NULL,
    name        VARCHAR2(20 CHAR) NOT NULL,
    -- Oracle은 빈 문자열을 NULL로 저장한다. 빈 소개 = NULL
    description VARCHAR2(200 CHAR),
    created_at  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT fk_channel_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_channel_member UNIQUE (member_id)
);
