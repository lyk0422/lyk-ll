-- 合成初始数据：设备状态与防误闭锁规则库（均为虚构合成数据）
INSERT INTO device_state (device_id, interval_id, device_type, bus_id, state, unlocked, updated_at)
SELECT 'BR-1101', 'INT-1101', 'BREAKER', 'BUS-1', 'ON', FALSE, TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE device_id = 'BR-1101');
INSERT INTO device_state (device_id, interval_id, device_type, bus_id, state, unlocked, updated_at)
SELECT 'DIS-1101', 'INT-1101', 'DISCONNECTOR', NULL, 'CLOSED', FALSE, TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE device_id = 'DIS-1101');
INSERT INTO device_state (device_id, interval_id, device_type, bus_id, state, unlocked, updated_at)
SELECT 'GW-1101', 'INT-1101', 'GROUND_WIRE', NULL, 'REMOVED', FALSE, TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE device_id = 'GW-1101');
INSERT INTO device_state (device_id, interval_id, device_type, bus_id, state, unlocked, updated_at)
SELECT 'BR-1102', 'INT-1102', 'BREAKER', 'BUS-1', 'ON', FALSE, TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE device_id = 'BR-1102');
INSERT INTO device_state (device_id, interval_id, device_type, bus_id, state, unlocked, updated_at)
SELECT 'DIS-1102', 'INT-1102', 'DISCONNECTOR', NULL, 'CLOSED', FALSE, TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE device_id = 'DIS-1102');
INSERT INTO device_state (device_id, interval_id, device_type, bus_id, state, unlocked, updated_at)
SELECT 'GW-1102', 'INT-1102', 'GROUND_WIRE', NULL, 'REMOVED', FALSE, TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE device_id = 'GW-1102');
INSERT INTO device_state (device_id, interval_id, device_type, bus_id, state, unlocked, updated_at)
SELECT 'BUS-1', 'INT-BUS', 'BUS', NULL, 'ENERGIZED', FALSE, TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE device_id = 'BUS-1');

INSERT INTO interlock_rule (rule_code, description, enabled)
SELECT 'R_LOAD_DISCONNECTOR', '带负荷拉合刀闸：间隔内开关合位时禁止拉合刀闸', TRUE
WHERE NOT EXISTS (SELECT 1 FROM interlock_rule WHERE rule_code = 'R_LOAD_DISCONNECTOR');
INSERT INTO interlock_rule (rule_code, description, enabled)
SELECT 'R_LIVE_GROUND', '带电挂接地线：间隔内开关合位时禁止挂接地线', TRUE
WHERE NOT EXISTS (SELECT 1 FROM interlock_rule WHERE rule_code = 'R_LIVE_GROUND');
INSERT INTO interlock_rule (rule_code, description, enabled)
SELECT 'R_GROUND_ENERGIZE', '带地线送电：间隔内接地线未拆除时禁止合开关送电', TRUE
WHERE NOT EXISTS (SELECT 1 FROM interlock_rule WHERE rule_code = 'R_GROUND_ENERGIZE');
INSERT INTO interlock_rule (rule_code, description, enabled)
SELECT 'R_BUS_SEQUENCE', '母线停电顺序：须先断开该母线全部出线开关', TRUE
WHERE NOT EXISTS (SELECT 1 FROM interlock_rule WHERE rule_code = 'R_BUS_SEQUENCE');
