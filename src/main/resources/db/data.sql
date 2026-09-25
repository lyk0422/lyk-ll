-- 合成危废经营许可数据（valid_to 含当日有效）；MERGE 保证测试 JVM 内重复初始化幂等
MERGE INTO hazardous_waste_license (license_no, holder, valid_from, valid_to, remark) KEY(license_no) VALUES
('HW-VALID-0001', '青山环保燃料有限公司', DATE '2020-01-01', DATE '2099-12-31', '长期有效合成许可'),
('HW-EXPIRED-0002', '陈集替代燃料厂', DATE '2018-03-01', DATE '2025-12-31', '已过期合成许可'),
('HW-VALID-0003', '江城水泥协同处置中心', DATE '2024-06-01', DATE '2027-05-31', '有效期内合成许可');
