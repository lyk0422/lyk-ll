-- 合成演示数据（仅 JVM 生命周期内保留；MERGE 保证多上下文重复初始化幂等）
MERGE INTO vehicle (id, plate_no, seats) KEY(id) VALUES
  (1, 'SYN-A001', 30),
  (2, 'SYN-A002', 56),
  (3, 'SYN-A003', 20);

MERGE INTO student (id, student_no, name) KEY(id) VALUES
  (1, 'S001', '合成学生一'),
  (2, 'S002', '合成学生二'),
  (3, 'S003', '合成学生三'),
  (4, 'S004', '合成学生四'),
  (5, 'S005', '合成学生五'),
  (6, 'S006', '合成学生六'),
  (7, 'S007', '合成学生七'),
  (8, 'S008', '合成学生八');

-- R001：已申请待评估线路（5 名学生，候选车辆 1）
MERGE INTO route (id, code, vehicle_id, status) KEY(id) VALUES (1, 'R001', 1, 'APPLIED');
MERGE INTO route_student (route_id, student_id) KEY(route_id, student_id) VALUES
  (1, 1), (1, 2), (1, 3), (1, 4), (1, 5);

-- R002：执行中线路（2 名学生，车辆 2 共 56 座，可接收改派），资格长期有效
MERGE INTO route (id, code, vehicle_id, status) KEY(id) VALUES (2, 'R002', 2, 'RUNNING');
MERGE INTO route_student (route_id, student_id) KEY(route_id, student_id) VALUES
  (2, 6), (2, 7);
MERGE INTO qualification (id, route_id, valid_from, valid_to, status) KEY(id) VALUES
  (1, 2, DATE '2026-01-01', DATE '2099-01-01', 'ACTIVE');

-- R003：执行中线路（1 名学生，车辆 3），资格 2026-01-10 到期，用于到期停运演示
MERGE INTO route (id, code, vehicle_id, status) KEY(id) VALUES (3, 'R003', 3, 'RUNNING');
MERGE INTO route_student (route_id, student_id) KEY(route_id, student_id) VALUES (3, 8);
MERGE INTO qualification (id, route_id, valid_from, valid_to, status) KEY(id) VALUES
  (2, 3, DATE '2025-01-10', DATE '2026-01-10', 'ACTIVE');
