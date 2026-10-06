-- 合成初始化数据：叶片台账与合成分级表（MERGE 幂等，可重复执行）

MERGE INTO blade (blade_code, turbine_id) KEY(blade_code) VALUES ('BLADE-A01', 'WTG-01');
MERGE INTO blade (blade_code, turbine_id) KEY(blade_code) VALUES ('BLADE-A02', 'WTG-01');
MERGE INTO blade (blade_code, turbine_id) KEY(blade_code) VALUES ('BLADE-B01', 'WTG-02');

MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('CRACK', 0.1000, 'SURFACE', 1);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('CRACK', 0.3000, 'SANDWICH', 2);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('CRACK', NULL, 'STRUCTURAL', 3);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('EROSION', 0.1500, 'SURFACE', 1);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('EROSION', 0.4000, 'SANDWICH', 2);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('EROSION', NULL, 'STRUCTURAL', 3);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('DELAMINATION', 0.0500, 'SURFACE', 1);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('DELAMINATION', 0.2000, 'SANDWICH', 2);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('DELAMINATION', NULL, 'STRUCTURAL', 3);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('*', 0.1000, 'SURFACE', 11);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('*', 0.3000, 'SANDWICH', 12);
MERGE INTO grading_rule (defect_type, max_depth_ratio, level, priority) KEY(defect_type, priority) VALUES ('*', NULL, 'STRUCTURAL', 13);
