-- 合成品种工艺主数据；MERGE 保证重复初始化幂等。
-- 工艺窗口车速单位 m/min，上料浓度单位 %，边界值均视为合格。

-- 70g 品种：灰分等级 1，车速窗口 800.000~1000.000 m/min，浓度窗口 0.800~1.200%
MERGE INTO grade (grade_code, grade_name, basis_weight, ash_level, speed_min, speed_max, consistency_min, consistency_max)
KEY(grade_code) VALUES ('G-A4-70', '合成70g双胶纸', 70.00, 1, 800.000, 1000.000, 0.800, 1.200);

-- 77g 品种：相对 70g 克重差恰 10%（直切边界），灰分等级 3（与 1 恰差 2 级，合法直切）
MERGE INTO grade (grade_code, grade_name, basis_weight, ash_level, speed_min, speed_max, consistency_min, consistency_max)
KEY(grade_code) VALUES ('G-A4-77', '合成77g双胶纸', 77.00, 3, 820.000, 1020.000, 0.850, 1.250);

-- 80g 品种：相对 70g 克重差 14.29%（超出 10% 须停机清洗），灰分等级 4（与 1 差 3 级须冲洗网部）
MERGE INTO grade (grade_code, grade_name, basis_weight, ash_level, speed_min, speed_max, consistency_min, consistency_max)
KEY(grade_code) VALUES ('G-A4-80', '合成80g双胶纸', 80.00, 4, 830.000, 1030.000, 0.900, 1.300);

-- 60g 品种：相对 70g 克重差 14.29%（超出 10%），灰分等级 2（与 1 差 1 级不冲洗）
MERGE INTO grade (grade_code, grade_name, basis_weight, ash_level, speed_min, speed_max, consistency_min, consistency_max)
KEY(grade_code) VALUES ('G-A4-60', '合成60g轻量纸', 60.00, 2, 900.000, 1100.000, 0.700, 1.100);

-- 84g 品种：相对 77g 克重差恰 9.09%（直切），灰分等级 0（与 3 差 3 级须冲洗网部）
MERGE INTO grade (grade_code, grade_name, basis_weight, ash_level, speed_min, speed_max, consistency_min, consistency_max)
KEY(grade_code) VALUES ('G-A4-84', '合成84g书刊纸', 84.00, 0, 850.000, 1050.000, 0.950, 1.350);
