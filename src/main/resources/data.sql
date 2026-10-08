-- 合成演示数据：仅用于本地运行，非真实数据
-- 库区邻接关系：A-B-C 为一个相邻组团，D 为独立库区
INSERT INTO archive_zone (code, name, adjacent_codes) VALUES
    ('A', '一号库区', 'B'),
    ('B', '二号库区', 'A,C'),
    ('C', '三号库区', 'B'),
    ('D', '四号库区', '');

-- 药剂库存：含安全线与有效期；第三条为已过期药剂，用于演示拒绝登记投放
INSERT INTO pesticide (name, quantity, unit, safety_line, expiry_date) VALUES
    ('磷化铝片剂', 100.00, '克', 20.00, DATE '2030-12-31'),
    ('溴甲烷溶液', 50.00, '毫升', 10.00, DATE '2030-06-30'),
    ('陈旧熏蒸剂', 30.00, '克', 5.00, DATE '2020-01-01');

-- 药剂批号：各批号库存之和等于药剂总库存；药剂 3 的批号随药剂一并过期
INSERT INTO pesticide_batch (pesticide_id, batch_no, quantity, expiry_date, status, version, created_at) VALUES
    (1, 'AL-2026-01', 60.00, DATE '2030-12-31', 'ACTIVE', 1, TIMESTAMP '2026-01-01 00:00:00'),
    (1, 'AL-2026-02', 40.00, DATE '2030-12-31', 'ACTIVE', 1, TIMESTAMP '2026-01-01 00:00:00'),
    (2, 'MB-2026-01', 50.00, DATE '2030-06-30', 'ACTIVE', 1, TIMESTAMP '2026-01-01 00:00:00'),
    (3, 'OLD-2019-01', 30.00, DATE '2020-01-01', 'ACTIVE', 1, TIMESTAMP '2026-01-01 00:00:00');
