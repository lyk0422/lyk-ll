-- 合成项目目录与机构能力目录初始化（仅演示用合成数据，非真实监管口径，可重复执行）

MERGE INTO item_catalog KEY(item) VALUES
    ('毒死蜱', 0.0500),
    ('克百威', 0.0200),
    ('氧乐果', 0.0200),
    ('多菌灵', 0.5000);

MERGE INTO agency_capability KEY(id)
    SELECT 1, 'LAB-A', '毒死蜱', 1 WHERE NOT EXISTS (SELECT 1 FROM agency_capability WHERE id = 1);
MERGE INTO agency_capability KEY(id)
    SELECT 2, 'LAB-A', '克百威', 1 WHERE NOT EXISTS (SELECT 1 FROM agency_capability WHERE id = 2);
MERGE INTO agency_capability KEY(id)
    SELECT 3, 'LAB-A', '氧乐果', 2 WHERE NOT EXISTS (SELECT 1 FROM agency_capability WHERE id = 3);
MERGE INTO agency_capability KEY(id)
    SELECT 4, 'LAB-A', '多菌灵', 1 WHERE NOT EXISTS (SELECT 1 FROM agency_capability WHERE id = 4);
MERGE INTO agency_capability KEY(id)
    SELECT 5, 'LAB-B', '毒死蜱', 3 WHERE NOT EXISTS (SELECT 1 FROM agency_capability WHERE id = 5);
MERGE INTO agency_capability KEY(id)
    SELECT 6, 'LAB-B', '多菌灵', 2 WHERE NOT EXISTS (SELECT 1 FROM agency_capability WHERE id = 6);
