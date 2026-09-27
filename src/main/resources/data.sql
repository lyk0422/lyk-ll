-- 合成基础数据：单架飞机、重心包线、业载站位与一个航班。
-- 使用 MERGE，保证脚本重复执行不产生重复数据。

MERGE INTO aircraft (id, tail_no, aircraft_type, oew_kg, oew_arm, mzfw_kg, mtow_kg, mlw_kg, lemac, mac_length, fuel_arm)
KEY (id) VALUES
(1, 'B-TEST', 'SYN-J21', 40000, 700.00, 55000, 65000, 60000, 625.00, 250.00, 690.00);

-- 逆时针多边形；(55000,14) 为顶点，55000→65000 段下边为斜边（14%→16%），边界合法
MERGE INTO envelope_point (id, aircraft_id, seq_no, weight_kg, mac_pct)
KEY (id) VALUES
(1, 1, 0, 40000, 14.0000),
(2, 1, 1, 55000, 14.0000),
(3, 1, 2, 65000, 16.0000),
(4, 1, 3, 65000, 30.0000),
(5, 1, 4, 55000, 32.0000),
(6, 1, 5, 40000, 32.0000);

MERGE INTO load_station (id, aircraft_id, station_code, arm, label)
KEY (id) VALUES
(1, 1, 'PAX_F',   600.00, '前舱旅客区'),
(2, 1, 'PAX_E',   760.00, '后舱旅客区'),
(3, 1, 'BAG_F',   540.00, '前货舱行李'),
(4, 1, 'BAG_A',   820.00, '后货舱行李'),
(5, 1, 'CARGO_F', 500.00, '前货舱货物'),
(6, 1, 'MAIL_A',  800.00, '后货舱邮件');

MERGE INTO flight (id, flight_no, origin, destination, aircraft_id, scheduled_time, created_at)
KEY (id) VALUES
(1, 'CA1234', 'PEK', 'SHA', 1, TIMESTAMP '2026-09-26 10:00:00', TIMESTAMP '2026-09-26 06:00:00');

-- 集装器舱位主数据：力臂为整数毫米（与英寸站位并存的合成主数据），载荷上限为整数公斤（含皮重，恰达合法）
MERGE INTO uld_position (id, aircraft_id, position_code, arm_mm, max_payload_kg, allowed_uld_types)
KEY (id) VALUES
(1, 1, 'ULD_FWD_1', 12700, 3000, 'AKE,AKH'),
(2, 1, 'ULD_FWD_2', 13716, 2000, 'AKE'),
(3, 1, 'ULD_AFT_1', 20828, 3000, 'AKE,PAG'),
(4, 1, 'ULD_AFT_2', 20320, 2000, 'AKH,PAG');
