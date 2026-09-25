-- 合成基础目录：功效目录与限用成分限值（仅合成数据，可重复执行）
-- 普通功效：资料齐全即可；特殊功效须上传对应功效评价试验报告。

MERGE INTO efficacy_catalog (code, name, special, supported) KEY (code) VALUES
  ('MOISTURIZING', '保湿', 0, 1),
  ('REFRESHING',   '爽肤', 0, 1),
  ('FRAGRANCE',    '芳香', 0, 1),
  ('SUNSCREEN',    '防晒', 1, 1),
  ('WHITENING',    '美白', 1, 1),
  ('FRECKLE',      '祛斑', 1, 1),
  ('ANTI_WRINKLE', '抗皱', 1, 1),
  ('HAIR_DYE',     '染发', 1, 1),
  ('HAIR_PERM',    '烫发', 1, 1),
  ('MEDICAL_CURE', '医疗治疗作用', 1, 0);

-- 限用成分限值（质量百分比浓度，恰等限值合法）
MERGE INTO restricted_ingredient (ingredient_code, name, max_concentration_pct, unit) KEY (ingredient_code) VALUES
  ('SALICYLIC_ACID', '水杨酸', 2.0000, '%'),
  ('PHENOXYETHANOL', '苯氧乙醇', 1.0000, '%'),
  ('NIACINAMIDE',    '烟酰胺', 5.0000, '%'),
  ('TITANIUM_DIOXIDE','二氧化钛', 25.0000, '%');
