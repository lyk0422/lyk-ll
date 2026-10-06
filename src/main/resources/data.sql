-- 合成初始数据：三条照明回路，初始档位3，未锁定、从未调整
INSERT INTO circuit (code, name, current_level, initial_level, locked, created_at) VALUES
('TUN-A-01', 'A隧道入口段回路1', 3, 3, 0, '2026-01-01 00:00:00'),
('TUN-A-02', 'A隧道中段回路2', 3, 3, 0, '2026-01-01 00:00:00'),
('TUN-B-01', 'B隧道出口段回路1', 3, 3, 0, '2026-01-01 00:00:00');
