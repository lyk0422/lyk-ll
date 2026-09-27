-- 合成种子数据：防误闭锁规则库与演示间隔设备状态
-- 间隔 INT-1101：开关 BR-1101 合位、刀闸 DS-1101 合位、接地线 GW-1101 已拆
-- 母线 BUS-1：馈线开关 BR-F1/BR-F2 合位

MERGE INTO interlock_rule (rule_code, description, enabled) KEY(rule_code) VALUES
    ('LOAD_DISCONNECTOR', '带负荷拉合刀闸闭锁：开关合位时禁止拉合刀闸', TRUE),
    ('LIVE_GROUND', '带电挂接地线闭锁：间隔带电时禁止挂接地线', TRUE),
    ('GROUNDED_ENERGIZE', '带地线送电闭锁：接地线未拆时禁止合开关送电', TRUE),
    ('BUS_OUTAGE_SEQUENCE', '母线停电顺序闭锁：须先断开全部出线开关', TRUE);

MERGE INTO device_state (device_id, interval_id, device_type, state, updated_at) KEY(device_id) VALUES
    ('BR-1101', 'INT-1101', 'BREAKER', 'CLOSED', TIMESTAMP '2026-01-01 00:00:00'),
    ('DS-1101', 'INT-1101', 'DISCONNECTOR', 'CLOSED', TIMESTAMP '2026-01-01 00:00:00'),
    ('GW-1101', 'INT-1101', 'GROUND', 'REMOVED', TIMESTAMP '2026-01-01 00:00:00'),
    ('BUS-1', 'BUS-1', 'BUS', 'ENERGIZED', TIMESTAMP '2026-01-01 00:00:00'),
    ('BR-F1', 'BUS-1', 'BREAKER', 'CLOSED', TIMESTAMP '2026-01-01 00:00:00'),
    ('BR-F2', 'BUS-1', 'BREAKER', 'CLOSED', TIMESTAMP '2026-01-01 00:00:00');

-- 合成接地线实物台账（编号唯一，初始均在库可用）
MERGE INTO ground_wire (wire_no, status, version, created_at, updated_at) KEY(wire_no) VALUES
    ('GW-SYN-001', 'AVAILABLE', 1, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00'),
    ('GW-SYN-002', 'AVAILABLE', 1, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00'),
    ('GW-SYN-003', 'AVAILABLE', 1, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00');
