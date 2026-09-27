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

-- ======================================================================
-- 儿童分龄合成规则（仅合成数据，可重复执行；不用于生产）
-- 分两档：0~36月、37~144月；跨档取两档较严限值，恰等限值合格。
-- ======================================================================

MERGE INTO child_age_rule (rule_version, tier_code, age_from_months, age_to_months, created_at_utc) KEY (rule_version, tier_code) VALUES
  ('CHILD-AGE-RULE-V1', 'TIER_0_36',   0,   36, CURRENT_TIMESTAMP),
  ('CHILD-AGE-RULE-V1', 'TIER_37_144', 37, 144, CURRENT_TIMESTAMP);

-- 儿童分档成分限值：低龄档更严；跨档成分按两档较严者判定
MERGE INTO child_ingredient_limit (rule_version, tier_code, ingredient_code, name, max_concentration_pct)
  KEY (rule_version, tier_code, ingredient_code) VALUES
  ('CHILD-AGE-RULE-V1', 'TIER_0_36',   'SALICYLIC_ACID',  '水杨酸',   0.5000),
  ('CHILD-AGE-RULE-V1', 'TIER_37_144', 'SALICYLIC_ACID',  '水杨酸',   1.0000),
  ('CHILD-AGE-RULE-V1', 'TIER_0_36',   'PHENOXYETHANOL',  '苯氧乙醇', 0.5000),
  ('CHILD-AGE-RULE-V1', 'TIER_37_144', 'PHENOXYETHANOL',  '苯氧乙醇', 1.0000),
  ('CHILD-AGE-RULE-V1', 'TIER_0_36',   'NIACINAMIDE',     '烟酰胺',   3.0000),
  ('CHILD-AGE-RULE-V1', 'TIER_37_144', 'NIACINAMIDE',     '烟酰胺',   5.0000),
  ('CHILD-AGE-RULE-V1', 'TIER_0_36',   'TITANIUM_DIOXIDE','二氧化钛', 20.0000),
  ('CHILD-AGE-RULE-V1', 'TIER_37_144', 'TITANIUM_DIOXIDE','二氧化钛', 25.0000);

-- 儿童允许功效目录：成人功效（如成人 SUNSCREEN 报告）不能替代儿童安全报告
MERGE INTO child_efficacy_catalog (code, name, supported) KEY (code) VALUES
  ('CHILD_MOISTURIZING', '儿童保湿', 1),
  ('CHILD_REFRESHING',   '儿童爽肤', 1),
  ('CHILD_SUNSCREEN',    '儿童防晒', 1),
  ('CHILD_WHITENING',    '儿童美白', 0);
