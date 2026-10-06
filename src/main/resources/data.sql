-- 合成叶片数据：仅用于本地运行与测试初始化，MERGE 保证重复初始化幂等。
MERGE INTO blade (blade_code, turbine_no, created_at) KEY(blade_code) VALUES
('BLADE-001', 'TURBINE-01', '2026-01-01 00:00:00'),
('BLADE-002', 'TURBINE-01', '2026-01-01 00:00:00'),
('BLADE-003', 'TURBINE-02', '2026-01-01 00:00:00');
