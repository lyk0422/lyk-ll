-- 合成基础目录：功效目录与限用成分限值（仅合成数据，可重复执行）
-- 普通功效：资料齐全即可；特殊功效须上传对应功效评价试验报告。
-- 儿童备案只允许 child_allowed=1 的合成儿童功效；成人功效报告不能替代儿童安全报告。

MERGE INTO efficacy_catalog (code, name, special, supported, child_allowed) KEY (code) VALUES
  ('MOISTURIZING', '保湿', 0, 1, 1),
  ('REFRESHING',   '爽肤', 0, 1, 1),
  ('FRAGRANCE',    '芳香', 0, 1, 0),
  ('SUNSCREEN',    '防晒', 1, 1, 0),
  ('WHITENING',    '美白', 1, 1, 0),
  ('FRECKLE',      '祛斑', 1, 1, 0),
  ('ANTI_WRINKLE', '抗皱', 1, 1, 0),
  ('HAIR_DYE',     '染发', 1, 1, 0),
  ('HAIR_PERM',    '烫发', 1, 1, 0),
  ('MEDICAL_CURE', '医疗治疗作用', 1, 0, 0);

-- 限用成分限值（质量百分比浓度，恰等限值合法）
-- 儿童分龄限值：0~36月档更严；跨档备案按两档较严者（取小）判定。
MERGE INTO restricted_ingredient (ingredient_code, name, max_concentration_pct, unit,
                                  child_max_band_0_36_pct, child_max_band_37_144_pct)
KEY (ingredient_code) VALUES
  ('SALICYLIC_ACID', '水杨酸', 2.0000, '%', 0.5000, 1.0000),
  ('PHENOXYETHANOL', '苯氧乙醇', 1.0000, '%', 0.4000, 1.0000),
  ('NIACINAMIDE',    '烟酰胺', 5.0000, '%', 2.0000, 5.0000),
  ('TITANIUM_DIOXIDE','二氧化钛', 25.0000, '%', 10.0000, 25.0000);

-- 分龄规则合成初始化：当前规则版本与两档定义（整数月闭区间）
MERGE INTO age_rule_version (id, rule_version, effective_from) KEY (id) VALUES
  (1, 'AGE-RULE-V1', DATE '2026-01-01');

MERGE INTO age_band_rule (rule_version, band_code, lower_months, upper_months)
KEY (rule_version, band_code) VALUES
  ('AGE-RULE-V1', 'BAND_0_36', 0, 36),
  ('AGE-RULE-V1', 'BAND_37_144', 37, 144);
