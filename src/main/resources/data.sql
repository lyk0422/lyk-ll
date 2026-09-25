-- 合成基础数据：预警级别与泵站台账。使用固定主键 MERGE，保证重启可重复初始化。

MERGE INTO warning_level (id, level_code, level_name, sort_no, trigger_pre_drain) KEY(id)
VALUES (1, 'BLUE',   '暴雨蓝色预警', 1, FALSE);
MERGE INTO warning_level (id, level_code, level_name, sort_no, trigger_pre_drain) KEY(id)
VALUES (2, 'YELLOW', '暴雨黄色预警', 2, FALSE);
MERGE INTO warning_level (id, level_code, level_name, sort_no, trigger_pre_drain) KEY(id)
VALUES (3, 'ORANGE', '暴雨橙色预警', 3, TRUE);
MERGE INTO warning_level (id, level_code, level_name, sort_no, trigger_pre_drain) KEY(id)
VALUES (4, 'RED',    '暴雨红色预警', 4, TRUE);

MERGE INTO pump_station (id, station_code, station_name, basin_zone, safety_low_level, run_status, gate_opening) KEY(id)
VALUES (10, 'DW-01', '东湾一号泵站', 'DONGWAN', 2.50, 'STOPPED', 0);
MERGE INTO pump_station (id, station_code, station_name, basin_zone, safety_low_level, run_status, gate_opening) KEY(id)
VALUES (11, 'DW-02', '东湾二号泵站', 'DONGWAN', 2.60, 'STOPPED', 0);
MERGE INTO pump_station (id, station_code, station_name, basin_zone, safety_low_level, run_status, gate_opening) KEY(id)
VALUES (20, 'XH-01', '西湖一号泵站', 'XIHU',   2.40, 'STOPPED', 0);
