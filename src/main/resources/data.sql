-- 合成隧道主数据：3 个防火分区，A/B 相邻且共用一台共享风阀，C 独立。
-- 幂等插入，重复启动不会产生重复数据。

MERGE INTO fire_zone (id, zone_code, zone_name) KEY (zone_code) VALUES
    (1, 'ZONE_A', '隧道A区（上游）'),
    (2, 'ZONE_B', '隧道B区（下游，与A相邻）'),
    (3, 'ZONE_C', '隧道C区（独立段）');

MERGE INTO device (id, device_code, device_name, device_type, home_zone, shared_flag) KEY (device_code) VALUES
    (1, 'FAN_A',         'A区排烟风机',   'FAN',    'ZONE_A', 0),
    (2, 'FAN_B',         'B区排烟风机',   'FAN',    'ZONE_B', 0),
    (3, 'FAN_C',         'C区排烟风机',   'FAN',    'ZONE_C', 0),
    (4, 'D_A_1',         'A区1号风阀',    'DAMPER', 'ZONE_A', 0),
    (5, 'D_A_2',         'A区2号风阀',    'DAMPER', 'ZONE_A', 0),
    (6, 'D_B_1',         'B区1号风阀',    'DAMPER', 'ZONE_B', 0),
    (7, 'D_AB_SHARED',   'A/B区共享联络风阀', 'DAMPER', 'ZONE_A', 1),
    (8, 'D_C_1',         'C区1号风阀',    'DAMPER', 'ZONE_C', 0);

-- 方向偏置：PLUS 表示与正向(FORWARD)同侧，烟流正向时目标 OPEN；
-- MINUS 表示反向侧，烟流反向(BACKWARD)时目标 OPEN；其余目标 CLOSE。
MERGE INTO zone_damper_ref (id, zone_code, damper_code, bias) KEY (zone_code, damper_code) VALUES
    (1, 'ZONE_A', 'D_A_1',       'PLUS'),
    (2, 'ZONE_A', 'D_A_2',       'PLUS'),
    (3, 'ZONE_A', 'D_AB_SHARED', 'PLUS'),
    (4, 'ZONE_B', 'D_B_1',       'PLUS'),
    (5, 'ZONE_B', 'D_AB_SHARED', 'MINUS'),
    (6, 'ZONE_C', 'D_C_1',       'PLUS');
