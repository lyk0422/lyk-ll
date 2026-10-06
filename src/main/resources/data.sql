-- 合成初始化数据，仅用于本地演示与测试，不含真实数据。

-- 档位功率字典：0 档熄灭，5 档满功率 400W。
INSERT INTO dim_level (level, watts) VALUES
    (0, 0),
    (1, 80),
    (2, 160),
    (3, 240),
    (4, 320),
    (5, 400);

-- 三条照明回路：入口段、中段、出口段；保底 1 档，最高 5 档，亮度阈值 300 lux。
INSERT INTO circuit (code, name, current_level, initial_level, base_level, max_level, brightness_threshold, status)
VALUES
    ('TUN-ENT-01', '入口段回路', 3, 3, 1, 5, 300, 'NORMAL'),
    ('TUN-MID-01', '中段回路', 2, 2, 1, 5, 300, 'NORMAL'),
    ('TUN-EXT-01', '出口段回路', 3, 3, 1, 5, 300, 'NORMAL');
