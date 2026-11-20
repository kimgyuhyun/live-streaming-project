CREATE TABLE member (
    id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR2(254)     NOT NULL,
    password_hash VARCHAR2(100)     NOT NULL,
    nickname      VARCHAR2(12 CHAR) NOT NULL,
    role          VARCHAR2(20)      NOT NULL,
    created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    -- 제약 이름을 MemberService가 읽어 이메일·닉네임 중복을 구분한다
    CONSTRAINT uk_member_email UNIQUE (email),
    CONSTRAINT uk_member_nickname UNIQUE (nickname),
    CONSTRAINT ck_member_role CHECK (role IN ('MEMBER', 'STREAMER', 'OPERATOR'))
);
