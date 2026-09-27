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

-- ============================ 儿童分龄规则合成初始化 ============================

-- 分龄规则（合成）：0~36 月 / 37~144 月两档，规则版本 1
MERGE INTO children_age_band_rule (band_code, lower_months, upper_months, rule_version) KEY (band_code) VALUES
  ('INFANT', 0, 36, 1),
  ('CHILD', 37, 144, 1);

-- 儿童分档限用成分限值（合成）：婴幼儿档更严，跨档按两档较严者判定，恰等限值合格
MERGE INTO children_band_limit (band_code, ingredient_code, max_concentration_pct) KEY (band_code, ingredient_code) VALUES
  ('INFANT', 'SALICYLIC_ACID',  0.5000),
  ('INFANT', 'PHENOXYETHANOL',  0.4000),
  ('INFANT', 'NIACINAMIDE',     2.0000),
  ('INFANT', 'TITANIUM_DIOXIDE', 25.0000),
  ('CHILD',  'SALICYLIC_ACID',  2.0000),
  ('CHILD',  'PHENOXYETHANOL',  1.0000),
  ('CHILD',  'NIACINAMIDE',     5.0000),
  ('CHILD',  'TITANIUM_DIOXIDE', 25.0000);

-- 儿童功效合成目录：仅目录内且 supported=1 的功效允许儿童宣称
MERGE INTO children_efficacy_catalog (code, name, supported) KEY (code) VALUES
  ('MOISTURIZING', '保湿', 1),
  ('REFRESHING',   '爽肤', 1),
  ('CLEANING',     '清洁', 1),
  ('SUNSCREEN',    '防晒', 1),
  ('WHITENING',    '美白', 0),
  ('FRECKLE',      '祛斑', 0),
  ('ANTI_WRINKLE', '抗皱', 0),
  ('HAIR_DYE',     '染发', 0),
  ('HAIR_PERM',    '烫发', 0),
  ('MEDICAL_CURE', '医疗治疗作用', 0);
