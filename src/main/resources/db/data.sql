-- 合成隧道数据：三个防火分区 Z1/Z2/Z3，设备均为模拟设备。
-- Z1、Z2 共用风阀 D-SHARED：两分区排烟预案对其目标状态不同，用于验证共享风阀冲突。
-- Z3 设备独立，用于验证相邻/独立分区并行互不阻塞。

MERGE INTO fire_zone (zone_code, zone_name, remark) KEY (zone_code) VALUES
('Z1', '一号防火分区', '合成数据：上游分区'),
('Z2', '二号防火分区', '合成数据：与 Z1 共用联络风道风阀'),
('Z3', '三号防火分区', '合成数据：独立风道分区');

MERGE INTO device (device_code, device_type, home_zone, shared_flag, device_name) KEY (device_code) VALUES
('D-Z1-1', 'DAMPER', 'Z1', 0, 'Z1 排烟风阀'),
('D-Z2-1', 'DAMPER', 'Z2', 0, 'Z2 排烟风阀'),
('D-SHARED', 'DAMPER', 'Z1', 1, 'Z1/Z2 联络风道共享风阀'),
('F-Z1-1', 'FAN', 'Z1', 0, 'Z1 排烟风机'),
('F-Z2-1', 'FAN', 'Z2', 0, 'Z2 排烟风机'),
('D-Z3-1', 'DAMPER', 'Z3', 0, 'Z3 排烟风阀'),
('F-Z3-1', 'FAN', 'Z3', 0, 'Z3 排烟风机');

-- 预案：step_order 较小者先动作（风阀全部早于风机，顺序不可颠倒）
MERGE INTO zone_device (zone_code, device_code, step_order, purge_target, reset_target) KEY (zone_code, device_code) VALUES
('Z1', 'D-Z1-1',   1, 'OPEN',  'CLOSED'),
('Z1', 'D-SHARED', 2, 'OPEN',  'CLOSED'),
('Z1', 'F-Z1-1',   3, 'RUNNING','STOPPED'),
('Z2', 'D-Z2-1',   1, 'OPEN',  'CLOSED'),
('Z2', 'D-SHARED', 2, 'CLOSED','OPEN'),
('Z2', 'F-Z2-1',   3, 'RUNNING','STOPPED'),
('Z3', 'D-Z3-1',   1, 'OPEN',  'CLOSED'),
('Z3', 'F-Z3-1',   3, 'RUNNING','STOPPED');

MERGE INTO zone_adjacency (zone_code, adjacent_zone) KEY (zone_code, adjacent_zone) VALUES
('Z1', 'Z2'),
('Z2', 'Z1'),
('Z2', 'Z3'),
('Z3', 'Z2');
