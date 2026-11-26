-- 팔로우(#37, PRD viewer 작은 결정). 같은 채널을 두 번 팔로우해도 한 줄(기본 키)
CREATE TABLE follow (
    member_id  NUMBER NOT NULL,
    channel_id NUMBER NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_follow PRIMARY KEY (member_id, channel_id),
    CONSTRAINT fk_follow_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_follow_channel FOREIGN KEY (channel_id) REFERENCES channel (id)
);

-- 채널의 팔로워 수는 이 색인으로 센다(기본 키는 member_id가 앞이라 채널로 찾지 못한다)
CREATE INDEX idx_follow_channel ON follow (channel_id);

-- 팔로워 수. 팔로우가 실제로 들어가거나 지워질 때만 같은 트랜잭션에서 ±1(FollowService)
ALTER TABLE channel ADD (follower_count NUMBER DEFAULT 0 NOT NULL);
