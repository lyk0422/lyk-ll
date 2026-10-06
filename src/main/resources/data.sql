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
