-- 合成基础数据：标准版本、食品分类、添加剂目录、限量、复合配料
-- 全部为虚构数据；限量单位 g/kg（以终产品质量计）

MERGE INTO standard_version (version, published_at) KEY(version) VALUES
('V1', TIMESTAMP '2025-01-01 00:00:00'),
('V2', TIMESTAMP '2026-01-01 00:00:00');

MERGE INTO food_category (category_code, name_cn) KEY(category_code) VALUES
('BEV-01', '果蔬汁类饮料'),
('BAKERY-01', '烘烤糕点');

MERGE INTO additive_catalog (additive_code, name_cn, function_type, unit) KEY(additive_code) VALUES
('P001', '山梨酸钾', 'PRESERVATIVE', 'g/kg'),
('P002', '苯甲酸钠', 'PRESERVATIVE', 'g/kg'),
('A001', '特丁基对苯二酚(TBHQ)', 'ANTIOXIDANT', 'g/kg');

-- V1 限量
MERGE INTO additive_limit (additive_code, category_code, standard_version, max_amount) KEY(additive_code, category_code, standard_version) VALUES
('P001', 'BEV-01', 'V1', 0.500000),
('P002', 'BEV-01', 'V1', 1.000000),
('A001', 'BAKERY-01', 'V1', 0.200000);

-- V2 限量：山梨酸钾在饮料中由 0.50 收紧至 0.30，其余不变
MERGE INTO additive_limit (additive_code, category_code, standard_version, max_amount) KEY(additive_code, category_code, standard_version) VALUES
('P001', 'BEV-01', 'V2', 0.300000),
('P002', 'BEV-01', 'V2', 1.000000),
('A001', 'BAKERY-01', 'V2', 0.200000);

MERGE INTO compound_ingredient (compound_code, name_cn) KEY(compound_code) VALUES
('C001', '复合果酱');

-- 复合果酱中山梨酸钾含量 1.0 g/kg（以配料自身质量计）
MERGE INTO compound_ingredient_additive (compound_code, additive_code, amount) KEY(compound_code, additive_code) VALUES
('C001', 'P001', 1.000000);
