-- 合成字典数据：原料、药物添加剂（上限/休药天数/允许动物种类）、配伍禁忌。
-- 全部为虚构数据，不引用任何真实监管库。

MERGE INTO ingredient (code, name) KEY(code) VALUES
    ('ING-CORN', '合成玉米粉'),
    ('ING-SOY', '合成豆粕'),
    ('ING-WHEAT', '合成麸皮');

-- 药物：最大添加比例（质量占比）、休药天数（UTC 日）。
MERGE INTO drug_additive (code, name, max_ratio, withdrawal_days, active) KEY(code) VALUES
    ('DRG-ENRO', '合成恩诺沙星预混剂', 0.020000, 8, TRUE),
    ('DRG-AMOX', '合成阿莫西林可溶性粉', 0.050000, 7, TRUE),
    ('DRG-TYLO', '合成泰乐菌素预混剂', 0.010000, 14, TRUE);

MERGE INTO drug_allowed_species (drug_code, species) KEY(drug_code, species) VALUES
    ('DRG-ENRO', 'CHICKEN'),
    ('DRG-ENRO', 'SWINE'),
    ('DRG-AMOX', 'CHICKEN'),
    ('DRG-AMOX', 'SWINE'),
    ('DRG-TYLO', 'SWINE');

-- 配伍禁忌（无序对，按编码字典序存放）：恩诺沙星 与 泰乐菌素 禁止同方。
MERGE INTO contraindication (drug_a_code, drug_b_code) KEY(drug_a_code, drug_b_code) VALUES
    ('DRG-ENRO', 'DRG-TYLO');
