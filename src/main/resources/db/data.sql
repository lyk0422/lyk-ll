-- 合成基础数据：预警级别、流域分区、泵站
MERGE INTO alert_level (code, name, rank_no, trigger_pumpdown) KEY(code) VALUES
('BLUE', '蓝色预警', 1, 0),
('YELLOW', '黄色预警', 2, 0),
('ORANGE', '橙色预警', 3, 1),
('RED', '红色预警', 4, 1);

MERGE INTO basin_zone (code, name) KEY(code) VALUES
('ZN', '南城河流域'),
('ZB', '北湾河流域');

MERGE INTO pump_station (code, name, zone_code, target_level, safe_low_level, gate_start_opening) KEY(code) VALUES
('PS-01', '沿江一号泵站', 'ZN', 2.500, 1.200, 80.00),
('PS-02', '湖东二号泵站', 'ZN', 2.600, 1.300, 75.00),
('PS-03', '北湾三号泵站', 'ZB', 2.400, 1.100, 70.00);
