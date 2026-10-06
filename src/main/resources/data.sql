-- 合成初始数据：一条线路、四个站点、三个分段（幂等可重复执行）

INSERT INTO wind_line (id, code, name, status)
SELECT 1, 'LINE-01', '云海索道', 'NORMAL'
WHERE NOT EXISTS (SELECT 1 FROM wind_line WHERE id = 1);

INSERT INTO wind_station (id, line_id, seq_no, name)
SELECT 1, 1, 1, '山麓站' WHERE NOT EXISTS (SELECT 1 FROM wind_station WHERE id = 1);
INSERT INTO wind_station (id, line_id, seq_no, name)
SELECT 2, 1, 2, '半山站' WHERE NOT EXISTS (SELECT 1 FROM wind_station WHERE id = 2);
INSERT INTO wind_station (id, line_id, seq_no, name)
SELECT 3, 1, 3, '风口站' WHERE NOT EXISTS (SELECT 1 FROM wind_station WHERE id = 3);
INSERT INTO wind_station (id, line_id, seq_no, name)
SELECT 4, 1, 4, '山顶站' WHERE NOT EXISTS (SELECT 1 FROM wind_station WHERE id = 4);

INSERT INTO wind_segment (id, line_id, seq_no, from_station_id, to_station_id)
SELECT 1, 1, 1, 1, 2 WHERE NOT EXISTS (SELECT 1 FROM wind_segment WHERE id = 1);
INSERT INTO wind_segment (id, line_id, seq_no, from_station_id, to_station_id)
SELECT 2, 1, 2, 2, 3 WHERE NOT EXISTS (SELECT 1 FROM wind_segment WHERE id = 2);
INSERT INTO wind_segment (id, line_id, seq_no, from_station_id, to_station_id)
SELECT 3, 1, 3, 3, 4 WHERE NOT EXISTS (SELECT 1 FROM wind_segment WHERE id = 3);
