-- 合成基础数据：文物、展馆、展柜（温湿度区间按材质设定）
INSERT INTO artifact (artifact_no, name, grade, material, created_at) VALUES
 ('A001', '青铜夔纹鼎', 1, 'BRONZE', TIMESTAMP '2026-01-01 00:00:00'),
 ('A002', '唐代丝织佛画', 1, 'SILK',   TIMESTAMP '2026-01-01 00:00:00'),
 ('A003', '越窑青瓷瓶',   2, 'CERAMIC', TIMESTAMP '2026-01-01 00:00:00');

INSERT INTO hall (hall_no, name) VALUES
 ('H1', '青铜馆'),
 ('H2', '织绣馆');

INSERT INTO showcase (showcase_no, hall_id, material, temp_min, temp_max, hum_min, hum_max, frozen, created_at) VALUES
 ('S001', 1, 'BRONZE',  15.00, 22.00, 35.00, 50.00, FALSE, TIMESTAMP '2026-01-01 00:00:00'),
 ('S002', 2, 'SILK',    18.00, 22.00, 50.00, 60.00, FALSE, TIMESTAMP '2026-01-01 00:00:00'),
 ('S003', 1, 'CERAMIC', 15.00, 25.00, 40.00, 60.00, FALSE, TIMESTAMP '2026-01-01 00:00:00');
