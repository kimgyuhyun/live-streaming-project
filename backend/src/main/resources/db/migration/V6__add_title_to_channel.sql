-- 방송 제목(확정 사양 4절, 1~50자). 채널에 둔다(PRD channel 작은 결정 — 방송 기록에는 남기지 않는다).
-- NULL = 정한 적 없음 → 응답에서 "{채널 이름}의 방송"
ALTER TABLE channel ADD (title VARCHAR2(50 CHAR));
