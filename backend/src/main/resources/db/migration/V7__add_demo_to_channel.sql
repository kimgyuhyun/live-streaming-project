-- 데모 채널 표시(확정 사양 10절). 운영자가 붙인다(#14, PRD channel C3). 0 = 아님, 1 = 데모
ALTER TABLE channel ADD (demo NUMBER(1) DEFAULT 0 NOT NULL CONSTRAINT ck_channel_demo CHECK (demo IN (0, 1)));
