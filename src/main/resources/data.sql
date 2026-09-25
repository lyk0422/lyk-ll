-- 合成核素数据；半衰期参数一律以本表为准
-- SYN-1H / SYN-10H 为整半衰期合成核素，便于衰变推算精确复算
INSERT INTO nuclide (code, name, half_life_seconds)
SELECT 'SYN-1H', '合成核素-1小时', 3600
WHERE NOT EXISTS (SELECT 1 FROM nuclide WHERE code = 'SYN-1H');

INSERT INTO nuclide (code, name, half_life_seconds)
SELECT 'SYN-10H', '合成核素-10小时', 36000
WHERE NOT EXISTS (SELECT 1 FROM nuclide WHERE code = 'SYN-10H');

INSERT INTO nuclide (code, name, half_life_seconds)
SELECT 'TC-99M', '合成锝-99m', 21660
WHERE NOT EXISTS (SELECT 1 FROM nuclide WHERE code = 'TC-99M');

INSERT INTO nuclide (code, name, half_life_seconds)
SELECT 'F-18', '合成氟-18', 6588
WHERE NOT EXISTS (SELECT 1 FROM nuclide WHERE code = 'F-18');
