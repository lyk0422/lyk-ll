-- 合成初始数据：组分罐、调合罐、成品规格。全部为合成数据，仅用于本地运行与演示。

INSERT INTO component_tank (code, name, remaining_volume, octane, sulfur, vapor_pressure)
SELECT 'T101', '高辛烷值组分罐', 1000.000, 95.000, 5.000, 60.000
WHERE NOT EXISTS (SELECT 1 FROM component_tank WHERE code = 'T101');

INSERT INTO component_tank (code, name, remaining_volume, octane, sulfur, vapor_pressure)
SELECT 'T102', '常规组分罐', 1000.000, 89.000, 12.000, 70.000
WHERE NOT EXISTS (SELECT 1 FROM component_tank WHERE code = 'T102');

INSERT INTO component_tank (code, name, remaining_volume, octane, sulfur, vapor_pressure)
SELECT 'T103', '低硫调和组分罐', 500.000, 97.000, 3.000, 55.000
WHERE NOT EXISTS (SELECT 1 FROM component_tank WHERE code = 'T103');

INSERT INTO blend_tank (code, name)
SELECT 'B1', '1号调合罐'
WHERE NOT EXISTS (SELECT 1 FROM blend_tank WHERE code = 'B1');

INSERT INTO blend_tank (code, name)
SELECT 'B2', '2号调合罐'
WHERE NOT EXISTS (SELECT 1 FROM blend_tank WHERE code = 'B2');

INSERT INTO product_spec (product_code, octane_limit, sulfur_limit, vapor_pressure_limit,
                          octane_margin, sulfur_margin, vapor_pressure_margin)
SELECT 'GAS92', 92.000, 10.000, 65.000, 0.300, 0.500, 1.000
WHERE NOT EXISTS (SELECT 1 FROM product_spec WHERE product_code = 'GAS92');

INSERT INTO product_spec (product_code, octane_limit, sulfur_limit, vapor_pressure_limit,
                          octane_margin, sulfur_margin, vapor_pressure_margin)
SELECT 'GAS95', 95.000, 8.000, 60.000, 0.300, 0.500, 1.000
WHERE NOT EXISTS (SELECT 1 FROM product_spec WHERE product_code = 'GAS95');

INSERT INTO product_spec (product_code, octane_limit, sulfur_limit, vapor_pressure_limit,
                          octane_margin, sulfur_margin, vapor_pressure_margin)
SELECT 'GAS98', 98.000, 5.000, 55.000, 0.300, 0.500, 1.000
WHERE NOT EXISTS (SELECT 1 FROM product_spec WHERE product_code = 'GAS98');
