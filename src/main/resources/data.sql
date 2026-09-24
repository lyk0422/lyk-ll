-- 本地演示用合成数据（非真实用户）；时间均为 UTC
-- 13800000001：无欠费、无在网约定、从未携转，资格通过
-- 13800000002：有欠费，资格拒绝
-- 13800000003：在网约定期限内，资格拒绝
-- 13800000004：上次携转于 2026-01-01（UTC），距演示日期已超 120 日，资格通过
MERGE INTO subscriber_profile (msisdn, has_arrears, in_contract, last_port_at, updated_at) KEY(msisdn) VALUES
    ('13800000001', 0, 0, NULL, CURRENT_TIMESTAMP),
    ('13800000002', 1, 0, NULL, CURRENT_TIMESTAMP),
    ('13800000003', 0, 1, NULL, CURRENT_TIMESTAMP),
    ('13800000004', 0, 0, TIMESTAMP '2026-01-01 00:00:00', CURRENT_TIMESTAMP);
