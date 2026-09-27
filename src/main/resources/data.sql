-- 合成基础数据：班组与重要用户分级（幂等写入，重复初始化不报错）

INSERT INTO repair_team (name)
SELECT '抢修一班' WHERE NOT EXISTS (SELECT 1 FROM repair_team WHERE name = '抢修一班');
INSERT INTO repair_team (name)
SELECT '抢修二班' WHERE NOT EXISTS (SELECT 1 FROM repair_team WHERE name = '抢修二班');

INSERT INTO important_user (user_name, level, feeder_id)
SELECT '市一医院', 'SPECIAL', 'F01'
WHERE NOT EXISTS (SELECT 1 FROM important_user WHERE user_name = '市一医院');
INSERT INTO important_user (user_name, level, feeder_id)
SELECT '地铁一号线', 'FIRST', 'F01'
WHERE NOT EXISTS (SELECT 1 FROM important_user WHERE user_name = '地铁一号线');
INSERT INTO important_user (user_name, level, feeder_id)
SELECT '数据中心A', 'SECOND', 'F02'
WHERE NOT EXISTS (SELECT 1 FROM important_user WHERE user_name = '数据中心A');

-- 合成发电车池：容量为正整数kW，仅服务登记馈线；request_id 为种子登记幂等键
INSERT INTO generator_vehicle (request_id, code, capacity_kw, feeder_id, available, created_at)
SELECT 'seed-vehicle-g1', '发电车G1', 500, 'F01', TRUE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM generator_vehicle WHERE code = '发电车G1');
INSERT INTO generator_vehicle (request_id, code, capacity_kw, feeder_id, available, created_at)
SELECT 'seed-vehicle-g2', '发电车G2', 300, 'F01', TRUE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM generator_vehicle WHERE code = '发电车G2');
INSERT INTO generator_vehicle (request_id, code, capacity_kw, feeder_id, available, created_at)
SELECT 'seed-vehicle-g3', '发电车G3', 800, 'F01', TRUE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM generator_vehicle WHERE code = '发电车G3');
INSERT INTO generator_vehicle (request_id, code, capacity_kw, feeder_id, available, created_at)
SELECT 'seed-vehicle-g4', '发电车G4', 400, 'F02', TRUE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM generator_vehicle WHERE code = '发电车G4');
