-- 合成初始化数据，仅用于本地免外部系统启动演示；测试库会在每个场景前清空。
INSERT INTO special_area (name, polygon_json, created_at)
SELECT 'DEMO-SPECIAL-AREA', '[[0.0,0.0],[10.0,0.0],[10.0,10.0],[0.0,10.0]]', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM special_area WHERE name = 'DEMO-SPECIAL-AREA');

INSERT INTO land_mark (name, longitude, latitude, created_at)
SELECT 'DEMO-LAND', 0.0, 0.0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM land_mark WHERE name = 'DEMO-LAND');

INSERT INTO ship (imo, name, created_at)
SELECT 'IMO-9000001', 'DEMO-SHIP', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM ship WHERE imo = 'IMO-9000001');

INSERT INTO voyage (ship_id, voyage_no, status, created_at)
SELECT s.id, 'V001', 'OPEN', CURRENT_TIMESTAMP
FROM ship s
WHERE s.imo = 'IMO-9000001'
  AND NOT EXISTS (SELECT 1 FROM voyage v WHERE v.ship_id = s.id AND v.voyage_no = 'V001');
