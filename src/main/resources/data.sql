-- 合成门站、流量计与加臭装置种子数据，仅存在于 JVM 生命周期内
-- 采用 NOT EXISTS 条件插入，保证多上下文重复初始化时幂等
INSERT INTO station (station_code, name, pressure_low_limit_mpa, pressure_high_limit_mpa,
                     pressure_target_mpa, target_concentration_mg_m3, concentration_tolerance_pct)
SELECT 'ST01', '东郊合成门站', 0.3000, 0.5000, 0.4000, 20.000, 20.00
WHERE NOT EXISTS (SELECT 1 FROM station WHERE station_code = 'ST01');
INSERT INTO station (station_code, name, pressure_low_limit_mpa, pressure_high_limit_mpa,
                     pressure_target_mpa, target_concentration_mg_m3, concentration_tolerance_pct)
SELECT 'ST02', '西郊合成门站', 0.2000, 0.4000, 0.3000, 20.000, 20.00
WHERE NOT EXISTS (SELECT 1 FROM station WHERE station_code = 'ST02');

INSERT INTO flow_meter (station_id, meter_code, meter_name)
SELECT id, 'FM01-ST01', '1号标况流量计' FROM station s
WHERE s.station_code = 'ST01'
  AND NOT EXISTS (SELECT 1 FROM flow_meter WHERE meter_code = 'FM01-ST01');
INSERT INTO flow_meter (station_id, meter_code, meter_name)
SELECT id, 'FM01-ST02', '2号标况流量计' FROM station s
WHERE s.station_code = 'ST02'
  AND NOT EXISTS (SELECT 1 FROM flow_meter WHERE meter_code = 'FM01-ST02');

INSERT INTO odorant_device (station_id, device_code, device_name)
SELECT id, 'OD01-ST01', '1号比例加臭装置' FROM station s
WHERE s.station_code = 'ST01'
  AND NOT EXISTS (SELECT 1 FROM odorant_device WHERE device_code = 'OD01-ST01');
INSERT INTO odorant_device (station_id, device_code, device_name)
SELECT id, 'OD01-ST02', '2号比例加臭装置' FROM station s
WHERE s.station_code = 'ST02'
  AND NOT EXISTS (SELECT 1 FROM odorant_device WHERE device_code = 'OD01-ST02');

INSERT INTO valve_state (station_id, injection_locked, alarm_active, breach_count,
                         current_injection_rate_mg_m3, current_pressure_mpa)
SELECT id, FALSE, FALSE, 0, 20.000, NULL FROM station s
WHERE NOT EXISTS (SELECT 1 FROM valve_state v WHERE v.station_id = s.id);
