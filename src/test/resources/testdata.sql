-- 测试合成数据：库区 A/B/C/D，邻接 A-B、B-C；药剂 1 正常、2 已过期、3 低库存
INSERT INTO archive_zone (code, name) VALUES
    ('A', '东区档案库'),
    ('B', '西区档案库'),
    ('C', '南区档案库'),
    ('D', '北区档案库');

INSERT INTO zone_adjacency (zone_code, adjacent_code) VALUES
    ('A', 'B'), ('B', 'A'),
    ('B', 'C'), ('C', 'B');

INSERT INTO pesticide (id, name, batch_no, quantity, unit, safety_line, expire_date) VALUES
    (1, '磷化铝片剂', 'PH-2026-01', 100.00, '瓶', 20.00, DATE '2027-12-31'),
    (2, '敌敌畏乳油', 'DDV-2024-09', 50.00, '升', 10.00, DATE '2025-01-01'),
    (3, '樟脑精块', 'ZN-2026-03', 12.00, '箱', 10.00, DATE '2027-06-30');
