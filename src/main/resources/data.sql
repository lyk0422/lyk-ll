-- 合成基础数据：小班、合成蓄积表与年度采伐限额（株数与蓄积双口径；日期口径 UTC）。

MERGE INTO forest_subplot (id, subplot_code, species_code, forest_category, protected_zone, stand_age, mature_age, area) KEY (id) VALUES
 ('SP-001', 'XB-0001', 'PINUS_MASSONIANA', 'COMMERCIAL',     0, 30, 25, 12.50), -- 马尾松商品林，林龄 30 >= 成熟龄 25
 ('SP-002', 'XB-0002', 'CUNNINGHAMIA',     'COMMERCIAL',     0, 20, 20,  8.00), -- 杉木商品林，林龄恰达成熟龄 20（合法边界）
 ('SP-003', 'XB-0003', 'PINUS_MASSONIANA', 'COMMERCIAL',     0, 10, 25,  6.00), -- 马尾松幼龄林，林龄 10 < 成熟龄 25（主伐拒绝，可抚育）
 ('SP-004', 'XB-0004', 'CUNNINGHAMIA',     'PUBLIC_WELFARE', 0, 40, 20, 15.00), -- 生态公益林（一律拒绝）
 ('SP-005', 'XB-0005', 'PINUS_MASSONIANA', 'COMMERCIAL',     1, 35, 25,  9.50), -- 禁伐区商品林（一律拒绝）
 ('SP-006', 'XB-0006', 'CUNNINGHAMIA',     'COMMERCIAL',     0, 26, 20,  5.00), -- 杉木商品林（并发发证串行校验用）
 ('SP-007', 'XB-0007', 'CUNNINGHAMIA',     'COMMERCIAL',     0, 22, 20,  4.00); -- 杉木商品林，林龄 22 无蓄积表档位（复核缺档拒绝用）

-- 合成蓄积表：复核蓄积 = 单株蓄积 × 申请株数（SP-007 林龄 22 故意无档位）
MERGE INTO species_volume_table (species_code, stand_age, volume_per_tree) KEY (species_code, stand_age) VALUES
 ('PINUS_MASSONIANA', 30, 0.5000), -- 马尾松 30 年：0.50 立方米/株
 ('PINUS_MASSONIANA', 10, 0.1000), -- 马尾松 10 年：0.10 立方米/株
 ('PINUS_MASSONIANA', 35, 0.6000), -- 马尾松 35 年：0.60 立方米/株
 ('CUNNINGHAMIA',     20, 0.4000), -- 杉木 20 年：0.40 立方米/株
 ('CUNNINGHAMIA',     26, 0.5000), -- 杉木 26 年：0.50 立方米/株
 ('CUNNINGHAMIA',     40, 0.8000); -- 杉木 40 年：0.80 立方米/株

-- 年度限额（双口径）：马尾松株数口径更紧（100 株），杉木蓄积口径更紧（50 立方米）
MERGE INTO annual_quota (species_code, quota_year, total_trees, total_volume) KEY (species_code, quota_year) VALUES
 ('PINUS_MASSONIANA', 2026, 100, 100.00), -- 马尾松 2026：100 株 / 100 立方米
 ('CUNNINGHAMIA',     2026, 200,  50.00), -- 杉木 2026：200 株 / 50 立方米
 ('PINUS_MASSONIANA', 2027, 100, 100.00),
 ('CUNNINGHAMIA',     2027, 200,  50.00),
 ('PINUS_MASSONIANA', 2028, 100, 100.00),
 ('CUNNINGHAMIA',     2028, 200,  50.00),
 ('PINUS_MASSONIANA', 2029, 100, 100.00),
 ('CUNNINGHAMIA',     2029, 200,  50.00);
