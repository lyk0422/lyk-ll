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
