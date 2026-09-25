-- 合成核素种子数据（半衰期参数唯一来源）
-- Tc-99m 半衰期 6.0067h = 21624s；F-18 约 109.77min = 6586s；TEST-1H 合成核素 3600s 便于整点复算
INSERT INTO nuclide (name, half_life_seconds)
SELECT 'Tc-99m', 21624 WHERE NOT EXISTS (SELECT 1 FROM nuclide WHERE name = 'Tc-99m');
INSERT INTO nuclide (name, half_life_seconds)
SELECT 'F-18', 6586 WHERE NOT EXISTS (SELECT 1 FROM nuclide WHERE name = 'F-18');
INSERT INTO nuclide (name, half_life_seconds)
SELECT 'TEST-1H', 3600 WHERE NOT EXISTS (SELECT 1 FROM nuclide WHERE name = 'TEST-1H');
