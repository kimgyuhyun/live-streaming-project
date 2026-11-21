-- 방송 한 번 = 송출이 시작돼서 끝날 때까지(확정 사양 5절). ended_at이 NULL이면 진행 중(#11)
CREATE TABLE broadcast (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id NUMBER    NOT NULL,
    started_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    ended_at   TIMESTAMP,
    CONSTRAINT fk_broadcast_channel FOREIGN KEY (channel_id) REFERENCES channel (id)
);

-- 채널당 진행 중 방송은 하나(PRD channel C2). 훅이 두 번 오거나 서버 2대가 동시에 대조해도 두 번째 INSERT가 여기서 막힌다.
-- Oracle에는 부분 유니크 인덱스가 없어 함수 기반 유니크 인덱스로 건다.
-- 끝난 방송은 식이 NULL이라 색인에 들어가지 않는다(V2의 uk_streamer_application_pending과 같은 방식)
CREATE UNIQUE INDEX uk_broadcast_live_channel
    ON broadcast (CASE WHEN ended_at IS NULL THEN channel_id END);
