-- 合成种子数据：单个门站，压力限值单位 kPa，浓度目标单位 mg/m³
MERGE INTO gate_station (station_code, station_name, odor_target, odor_tolerance, pressure_low, pressure_high, odor_consumed, valve_state)
KEY (station_code)
VALUES ('ST-001', '城东门站（合成）', 20.0000, 0.2000, 200.0000, 400.0000, 0.000000, 'OPEN');
