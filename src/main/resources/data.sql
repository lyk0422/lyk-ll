-- 合成基础数据：小班与年度采伐限额（单位：立方米；日期口径 UTC）。

MERGE INTO forest_subplot (id, subplot_code, species_code, forest_category, protected_zone, stand_age, mature_age, area) KEY (id) VALUES
 ('SP-001', 'XB-0001', 'PINUS_MASSONIANA', 'COMMERCIAL',     0, 30, 25, 12.50), -- 马尾松商品林，林龄 30 >= 成熟龄 25
 ('SP-002', 'XB-0002', 'CUNNINGHAMIA',     'COMMERCIAL',     0, 20, 20,  8.00), -- 杉木商品林，林龄恰达成熟龄 20（合法边界）
 ('SP-003', 'XB-0003', 'PINUS_MASSONIANA', 'COMMERCIAL',     0, 10, 25,  6.00), -- 马尾松幼龄林，林龄 10 < 成熟龄 25（主伐拒绝）
 ('SP-004', 'XB-0004', 'CUNNINGHAMIA',     'PUBLIC_WELFARE', 0, 40, 20, 15.00), -- 生态公益林（一律拒绝）
 ('SP-005', 'XB-0005', 'PINUS_MASSONIANA', 'COMMERCIAL',     1, 35, 25,  9.50), -- 禁伐区商品林（一律拒绝）
 ('SP-006', 'XB-0006', 'CUNNINGHAMIA',     'COMMERCIAL',     0, 26, 20,  5.00); -- 杉木商品林（并发发证串行校验用）

MERGE INTO annual_quota (species_code, quota_year, total_volume) KEY (species_code, quota_year) VALUES
 ('PINUS_MASSONIANA', 2026, 100.00), -- 马尾松 2026 年度限额 100 立方米
 ('CUNNINGHAMIA',     2026,  50.00), -- 杉木 2026 年度限额 50 立方米
 ('PINUS_MASSONIANA', 2027, 100.00),
 ('CUNNINGHAMIA',     2027,  50.00),
 ('PINUS_MASSONIANA', 2028, 100.00),
 ('CUNNINGHAMIA',     2028,  50.00),
 ('PINUS_MASSONIANA', 2029, 100.00),
 ('CUNNINGHAMIA',     2029,  50.00);
