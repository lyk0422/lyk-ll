-- 合成基础目录数据；H2 MODE=MySQL 下使用 MERGE INTO 实现可重入初始化。
MERGE INTO disease_catalog (code, name, category, enabled) KEY (code) VALUES
  ('PLAGUE',  '鼠疫',   'A', 1),
  ('CHOLERA', '霍乱',   'A', 1),
  ('MEASLES', '麻疹',   'B', 1),
  ('TYPHOID', '伤寒',   'B', 1),
  ('COVID19', '新型冠状病毒感染', 'B', 1),
  ('TB',      '肺结核', 'B', 1);

MERGE INTO medical_facility (code, name, enabled) KEY (code) VALUES
  ('F001', '第一人民医院（合成）', 1),
  ('F002', '城东社区卫生服务中心（合成）', 1);
