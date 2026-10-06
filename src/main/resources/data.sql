-- 合成初始化数据：一条线路、四个站点、三个分段，仅供本地演示与测试。
INSERT INTO ski_line (code, name, status, created_at)
SELECT 'YH-01', '云海索道一号线', 'NORMAL', TIMESTAMP '2026-01-01 08:00:00'
WHERE NOT EXISTS (SELECT 1 FROM ski_line WHERE code = 'YH-01');

INSERT INTO station (line_id, seq, name)
SELECT l.id, s.seq, s.name FROM ski_line l
JOIN (
    SELECT 1 AS seq, '山麓站' AS name UNION ALL
    SELECT 2, '松谷站' UNION ALL
    SELECT 3, '云台站' UNION ALL
    SELECT 4, '峰顶站'
) s ON l.code = 'YH-01'
WHERE NOT EXISTS (SELECT 1 FROM station st WHERE st.line_id = l.id AND st.seq = s.seq);

INSERT INTO segment (line_id, seq, from_station_seq, to_station_seq, name)
SELECT l.id, g.seq, g.seq, g.seq + 1, g.name FROM ski_line l
JOIN (
    SELECT 1 AS seq, '山麓-松谷段' AS name UNION ALL
    SELECT 2, '松谷-云台段' UNION ALL
    SELECT 3, '云台-峰顶段'
) g ON l.code = 'YH-01'
WHERE NOT EXISTS (SELECT 1 FROM segment sg WHERE sg.line_id = l.id AND sg.seq = g.seq);
