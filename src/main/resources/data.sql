-- 合成初始化数据：发酵罐与默认拉格工艺曲线（LAGER-CURVE v1）
-- 主酵 10.0℃ 72h -> 降温 4.0℃ 48h -> 后熟 -1.0℃ 168h（7 天）

INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-1', 5000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-1');
INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-2', 5000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-2');
INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-3', 8000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-3');

INSERT INTO process_curve (id, curve_code, version, status, created_at)
SELECT 1, 'LAGER-CURVE', 1, 'ACTIVE', TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM process_curve WHERE curve_code = 'LAGER-CURVE' AND version = 1);

INSERT INTO curve_segment (curve_id, phase, seq, setpoint_celsius, duration_hours)
SELECT 1, 'MAIN_FERMENT', 1, 10.00, 72
WHERE NOT EXISTS (SELECT 1 FROM curve_segment WHERE curve_id = 1 AND phase = 'MAIN_FERMENT' AND seq = 1);
INSERT INTO curve_segment (curve_id, phase, seq, setpoint_celsius, duration_hours)
SELECT 1, 'COOLING', 1, 4.00, 48
WHERE NOT EXISTS (SELECT 1 FROM curve_segment WHERE curve_id = 1 AND phase = 'COOLING' AND seq = 1);
INSERT INTO curve_segment (curve_id, phase, seq, setpoint_celsius, duration_hours)
SELECT 1, 'MATURATION', 1, -1.00, 168
WHERE NOT EXISTS (SELECT 1 FROM curve_segment WHERE curve_id = 1 AND phase = 'MATURATION' AND seq = 1);

-- 合成酵母单元：YEAST-001 全新代次 0 可用（无回收时刻，不受 72 小时限制）
INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at, occupied_by_batch_id, version, created_at)
SELECT 'YEAST-001', 0, 'AVAILABLE', NULL, NULL, 1, TIMESTAMP '2026-09-25 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM yeast_unit WHERE unit_code = 'YEAST-001');
-- YEAST-002 已回收 1 次（代次 1），2026-09-26 08:00 回收，可用于演示 72 小时新鲜度边界
INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at, occupied_by_batch_id, version, created_at)
SELECT 'YEAST-002', 1, 'AVAILABLE', TIMESTAMP '2026-09-26 08:00:00', NULL, 2, TIMESTAMP '2026-09-24 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM yeast_unit WHERE unit_code = 'YEAST-002');
