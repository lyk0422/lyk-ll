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

-- 分龄规则版本 CHILD-AGE-2026A：0~36月 / 37~144月两档（整数月闭区间）
MERGE INTO child_age_rule (rule_version, band_code, band_lower_months, band_upper_months, active)
  KEY (rule_version, band_code) VALUES
  ('CHILD-AGE-2026A', 'BAND_0_36',   0,  36, 1),
  ('CHILD-AGE-2026A', 'BAND_37_144', 37, 144, 1);

-- 儿童限用成分分档限值：跨档按较严（较低）者判定，恰等限值合格
MERGE INTO child_restricted_ingredient (rule_version, ingredient_code, band_code, max_concentration_pct)
  KEY (rule_version, ingredient_code, band_code) VALUES
  ('CHILD-AGE-2026A', 'PHENOXYETHANOL', 'BAND_0_36',   0.4000),
  ('CHILD-AGE-2026A', 'PHENOXYETHANOL', 'BAND_37_144', 1.0000),
  ('CHILD-AGE-2026A', 'SALICYLIC_ACID', 'BAND_0_36',   0.5000),
  ('CHILD-AGE-2026A', 'SALICYLIC_ACID', 'BAND_37_144', 2.0000),
  ('CHILD-AGE-2026A', 'NIACINAMIDE',    'BAND_0_36',   2.0000),
  ('CHILD-AGE-2026A', 'NIACINAMIDE',    'BAND_37_144', 5.0000);

-- 儿童功效合成目录：仅目录内且支持的功效可宣称；CH_WHITENING 为不支持示例
MERGE INTO child_efficacy_catalog (code, name, supported) KEY (code) VALUES
  ('CH_MOISTURIZE', '保湿',   1),
  ('CH_SOOTHE',     '舒缓',   1),
  ('CH_CLEANSE',    '清洁',   1),
  ('CH_SUNSCREEN',  '儿童防晒', 1),
  ('CH_WHITENING',  '美白',   0);
