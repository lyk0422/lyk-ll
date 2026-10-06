-- 合成初始化数据，仅用于本地演示与测试，不含真实个人信息。
-- 通过唯一键守卫保证重复初始化（如多个应用上下文共享内存库）时幂等。

INSERT INTO vehicle (plate_no, seat_capacity)
SELECT 'SYN-A0001', 40 WHERE NOT EXISTS (SELECT 1 FROM vehicle WHERE plate_no = 'SYN-A0001');
INSERT INTO vehicle (plate_no, seat_capacity)
SELECT 'SYN-A0002', 30 WHERE NOT EXISTS (SELECT 1 FROM vehicle WHERE plate_no = 'SYN-A0002');

INSERT INTO route (route_code, name, vehicle_id, station_spacing_m, road_grade, required_seats, status)
SELECT 'ROUTE-SEED-1', '合成示范线路一', v.id, 800, 2, 35, 'APPLIED'
FROM vehicle v
WHERE v.plate_no = 'SYN-A0001'
  AND NOT EXISTS (SELECT 1 FROM route WHERE route_code = 'ROUTE-SEED-1');

INSERT INTO student (student_no, name, route_id)
SELECT 'STU-0001', '合成学生甲', r.id FROM route r
WHERE r.route_code = 'ROUTE-SEED-1'
  AND NOT EXISTS (SELECT 1 FROM student WHERE student_no = 'STU-0001');
INSERT INTO student (student_no, name, route_id)
SELECT 'STU-0002', '合成学生乙', r.id FROM route r
WHERE r.route_code = 'ROUTE-SEED-1'
  AND NOT EXISTS (SELECT 1 FROM student WHERE student_no = 'STU-0002');
INSERT INTO student (student_no, name, route_id)
SELECT 'STU-0003', '合成学生丙', r.id FROM route r
WHERE r.route_code = 'ROUTE-SEED-1'
  AND NOT EXISTS (SELECT 1 FROM student WHERE student_no = 'STU-0003');
