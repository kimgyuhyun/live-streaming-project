-- 스트림 키는 해시와 앞 4자리만 저장한다(PRD channel C1). 키가 없으면 둘 다 NULL.
-- 해시는 SHA-256 소문자 16진수 64자
ALTER TABLE channel ADD (
    stream_key_hash   VARCHAR2(64),
    stream_key_prefix VARCHAR2(4)
);
