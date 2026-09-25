-- 每个测试方法前重置为干净的合成数据（顺序：先业务表后基础表）
DELETE FROM production_report;
DELETE FROM filing;
DELETE FROM recipe_ingredient;
DELETE FROM recipe_additive;
DELETE FROM recipe;
DELETE FROM idempotent_request;
DELETE FROM additive_limit;
DELETE FROM compound_ingredient_additive;
DELETE FROM compound_ingredient;
DELETE FROM additive_catalog;
DELETE FROM food_category;
DELETE FROM standard_version;

INSERT INTO standard_version (version, published_at) VALUES
('V1', TIMESTAMP '2025-01-01 00:00:00'),
('V2', TIMESTAMP '2026-01-01 00:00:00');

INSERT INTO food_category (category_code, name_cn) VALUES
('BEV-01', '果蔬汁类饮料'),
('BAKERY-01', '烘烤糕点');

INSERT INTO additive_catalog (additive_code, name_cn, function_type, unit) VALUES
('P001', '山梨酸钾', 'PRESERVATIVE', 'g/kg'),
('P002', '苯甲酸钠', 'PRESERVATIVE', 'g/kg'),
('A001', '特丁基对苯二酚(TBHQ)', 'ANTIOXIDANT', 'g/kg');

INSERT INTO additive_limit (additive_code, category_code, standard_version, max_amount) VALUES
('P001', 'BEV-01', 'V1', 0.500000),
('P002', 'BEV-01', 'V1', 1.000000),
('A001', 'BAKERY-01', 'V1', 0.200000),
('P001', 'BEV-01', 'V2', 0.300000),
('P002', 'BEV-01', 'V2', 1.000000),
('A001', 'BAKERY-01', 'V2', 0.200000);

INSERT INTO compound_ingredient (compound_code, name_cn) VALUES
('C001', '复合果酱');

INSERT INTO compound_ingredient_additive (compound_code, additive_code, amount) VALUES
('C001', 'P001', 1.000000);
