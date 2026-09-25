-- 本地演示用合成数据（非真实数据）
-- 小班
INSERT INTO forest_plot (code, forest_type, in_no_harvest_zone, stand_age, mature_age, species_tier, area_ha)
SELECT 'PLOT-C001', 'COMMERCIAL', FALSE, 30, 25, 'PINE', 12.50
WHERE NOT EXISTS (SELECT 1 FROM forest_plot WHERE code = 'PLOT-C001');
INSERT INTO forest_plot (code, forest_type, in_no_harvest_zone, stand_age, mature_age, species_tier, area_ha)
SELECT 'PLOT-C002', 'COMMERCIAL', FALSE, 40, 30, 'FIR', 8.00
WHERE NOT EXISTS (SELECT 1 FROM forest_plot WHERE code = 'PLOT-C002');
INSERT INTO forest_plot (code, forest_type, in_no_harvest_zone, stand_age, mature_age, species_tier, area_ha)
SELECT 'PLOT-E001', 'ECOLOGICAL', FALSE, 45, 30, 'PINE', 20.00
WHERE NOT EXISTS (SELECT 1 FROM forest_plot WHERE code = 'PLOT-E001');
INSERT INTO forest_plot (code, forest_type, in_no_harvest_zone, stand_age, mature_age, species_tier, area_ha)
SELECT 'PLOT-N001', 'COMMERCIAL', TRUE, 35, 25, 'PINE', 6.00
WHERE NOT EXISTS (SELECT 1 FROM forest_plot WHERE code = 'PLOT-N001');
INSERT INTO forest_plot (code, forest_type, in_no_harvest_zone, stand_age, mature_age, species_tier, area_ha)
SELECT 'PLOT-Y001', 'COMMERCIAL', FALSE, 20, 25, 'FIR', 9.50
WHERE NOT EXISTS (SELECT 1 FROM forest_plot WHERE code = 'PLOT-Y001');

-- 年度采伐限额（按树种分档，单位：立方米）
INSERT INTO annual_quota (quota_year, species_tier, quota_volume, used_volume)
SELECT 2026, 'PINE', 1000.00, 0 WHERE NOT EXISTS (SELECT 1 FROM annual_quota WHERE quota_year = 2026 AND species_tier = 'PINE');
INSERT INTO annual_quota (quota_year, species_tier, quota_volume, used_volume)
SELECT 2026, 'FIR', 500.00, 0 WHERE NOT EXISTS (SELECT 1 FROM annual_quota WHERE quota_year = 2026 AND species_tier = 'FIR');
INSERT INTO annual_quota (quota_year, species_tier, quota_volume, used_volume)
SELECT 2027, 'PINE', 1000.00, 0 WHERE NOT EXISTS (SELECT 1 FROM annual_quota WHERE quota_year = 2027 AND species_tier = 'PINE');
INSERT INTO annual_quota (quota_year, species_tier, quota_volume, used_volume)
SELECT 2027, 'FIR', 500.00, 0 WHERE NOT EXISTS (SELECT 1 FROM annual_quota WHERE quota_year = 2027 AND species_tier = 'FIR');
