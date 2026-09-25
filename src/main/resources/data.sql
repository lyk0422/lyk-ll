-- 合成授权用户种子（仅 H2 本地/测试）；揭盲须两名不同授权人先后确认
MERGE INTO authorized_user (user_id, display_name, enabled, created_at_utc) KEY (user_id)
VALUES ('INV-001', '张医生（主要研究者）', 1, CURRENT_TIMESTAMP);
MERGE INTO authorized_user (user_id, display_name, enabled, created_at_utc) KEY (user_id)
VALUES ('INV-002', '李医生（授权监察员）', 1, CURRENT_TIMESTAMP);
MERGE INTO authorized_user (user_id, display_name, enabled, created_at_utc) KEY (user_id)
VALUES ('INV-003', '王医生（独立监督员）', 1, CURRENT_TIMESTAMP);
