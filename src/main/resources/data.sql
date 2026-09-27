-- 合成初始化数据：发酵罐与默认拉格工艺曲线（LAGER-CURVE v1）
-- 主酵 10.0℃ 72h -> 降温 4.0℃ 48h -> 后熟 -1.0℃ 168h（7 天）

INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-1', 5000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-1');
INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-2', 5000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-2');
INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-3', 8000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-3');
INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-4', 5000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-4');
INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-5', 5000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-5');
INSERT INTO fermentation_tank (tank_code, capacity_liters)
SELECT 'TANK-6', 5000 WHERE NOT EXISTS (SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-6');

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

-- 合成酵母单元：初始代次 0、可用、版本 1、无回收时刻（首次单元不受 72 小时失效限制）
INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at, version, active_batch_id)
SELECT 'YEAST-1', 0, 'AVAILABLE', NULL, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM yeast_unit WHERE unit_code = 'YEAST-1');
INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at, version, active_batch_id)
SELECT 'YEAST-2', 0, 'AVAILABLE', NULL, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM yeast_unit WHERE unit_code = 'YEAST-2');
