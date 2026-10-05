-- 合成基础目录数据（H2 MySQL 兼容模式）
MERGE INTO disease (code, name, category) KEY (code) VALUES
    ('A01', '鼠疫',   'A'),
    ('A02', '霍乱',   'A'),
    ('B01', '病毒性肝炎', 'B'),
    ('B02', '肺结核', 'B'),
    ('B03', '梅毒',   'B');

MERGE INTO institution (code, name) KEY (code) VALUES
    ('H001', '示范市第一人民医院'),
    ('H002', '示范区疾病预防控制中心');

-- 合成暴露场所库：报告卡现住址包含关键词即自动归并
MERGE INTO exposure_site (code, name, keyword) KEY (code) VALUES
    ('S001', '示范市第一小学', '第一小学'),
    ('S002', '示范市合成路农贸市场', '农贸市场');
