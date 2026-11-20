CREATE TABLE streamer_application (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id  NUMBER       NOT NULL,
    status     VARCHAR2(20) NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT fk_streamer_application_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_streamer_application_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- 회원당 대기 신청은 하나(PRD channel "신청 동시 처리").
-- 대기가 아닌 행은 식이 NULL이라 색인에 들어가지 않으므로, 대기인 행끼리만 member_id가 겹치지 않는다
CREATE UNIQUE INDEX uk_streamer_application_pending
    ON streamer_application (CASE WHEN status = 'PENDING' THEN member_id END);
