-- 合成里程数据（仅演示与测试用途，非真实线网）
-- L1: A-B 3km, B-C 3km, C-D 4km
-- L2: C-E 5km, E-F 5km（C 为 L1/L2 换乘站）
-- L3: D-G 6km, G-H 4km（D 为 L1/L3 换乘站）
INSERT INTO metro_segment (line_code, from_station, to_station, distance_m)
SELECT 'L1', 'A', 'B', 3000 WHERE NOT EXISTS (SELECT 1 FROM metro_segment WHERE line_code='L1' AND from_station='A' AND to_station='B');
INSERT INTO metro_segment (line_code, from_station, to_station, distance_m)
SELECT 'L1', 'B', 'C', 3000 WHERE NOT EXISTS (SELECT 1 FROM metro_segment WHERE line_code='L1' AND from_station='B' AND to_station='C');
INSERT INTO metro_segment (line_code, from_station, to_station, distance_m)
SELECT 'L1', 'C', 'D', 4000 WHERE NOT EXISTS (SELECT 1 FROM metro_segment WHERE line_code='L1' AND from_station='C' AND to_station='D');
INSERT INTO metro_segment (line_code, from_station, to_station, distance_m)
SELECT 'L2', 'C', 'E', 5000 WHERE NOT EXISTS (SELECT 1 FROM metro_segment WHERE line_code='L2' AND from_station='C' AND to_station='E');
INSERT INTO metro_segment (line_code, from_station, to_station, distance_m)
SELECT 'L2', 'E', 'F', 5000 WHERE NOT EXISTS (SELECT 1 FROM metro_segment WHERE line_code='L2' AND from_station='E' AND to_station='F');
INSERT INTO metro_segment (line_code, from_station, to_station, distance_m)
SELECT 'L3', 'D', 'G', 6000 WHERE NOT EXISTS (SELECT 1 FROM metro_segment WHERE line_code='L3' AND from_station='D' AND to_station='G');
INSERT INTO metro_segment (line_code, from_station, to_station, distance_m)
SELECT 'L3', 'G', 'H', 4000 WHERE NOT EXISTS (SELECT 1 FROM metro_segment WHERE line_code='L3' AND from_station='G' AND to_station='H');
