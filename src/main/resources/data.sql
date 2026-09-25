-- 合成初始化数据：渔船、鱼种与 2026 年度（UTC）配额台账，仅用于本地演示，不含真实数据。

INSERT INTO species (code, name, protected_flag) VALUES
    ('COD', '鳕鱼', FALSE),
    ('HAR', '带鱼', FALSE),
    ('YFT', '金枪鱼', FALSE),
    ('PSH', '保护鲨', TRUE),
    ('TUR', '海龟', TRUE);

INSERT INTO vessel (id, vessel_no, name) VALUES
    (1, 'V001', '远洋一号'),
    (2, 'V002', '远洋二号');

INSERT INTO quota_ledger (vessel_id, species_code, quota_year, total_quota, used_quota, frozen) VALUES
    (1, 'COD', 2026, 10000.000, 0, FALSE),
    (1, 'HAR', 2026, 8000.000, 0, FALSE),
    (1, 'YFT', 2026, 5000.000, 0, FALSE),
    (1, 'PSH', 2026, 500.000, 0, FALSE),
    (1, 'TUR', 2026, 200.000, 0, FALSE),
    (2, 'COD', 2026, 10000.000, 0, FALSE),
    (2, 'HAR', 2026, 8000.000, 0, FALSE),
    (2, 'YFT', 2026, 5000.000, 0, FALSE),
    (2, 'PSH', 2026, 500.000, 0, FALSE),
    (2, 'TUR', 2026, 200.000, 0, FALSE);
