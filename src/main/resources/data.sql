-- 合成种子数据（仅 H2 本地，JVM 生命周期内有效）：
-- 1 名合成患者、1 套盆腔约束模板（2 项约束，剂量单位 cGy）。
-- 示范计划由 DataSeeder 走真实写入路径创建，避免手写自增主键。
MERGE INTO patient (patient_id, patient_name, created_at) KEY (patient_id)
VALUES ('P1001', '合成患者-甲', TIMESTAMP '2026-09-26 09:00:00');

MERGE INTO constraint_template (template_code, template_name, created_at) KEY (template_code)
VALUES ('TPL_PELVIS', '盆腔放疗合成约束模板', TIMESTAMP '2026-09-26 09:05:00');

MERGE INTO constraint_item
    (template_code, organ_code, organ_name, metric_type, metric_ref, limit_value)
KEY (template_code, organ_code, metric_type, metric_ref)
VALUES ('TPL_PELVIS', 'SPINAL_CORD', '脊髓', 'DMAX_CGY', NULL, 4500.00);

MERGE INTO constraint_item
    (template_code, organ_code, organ_name, metric_type, metric_ref, limit_value)
KEY (template_code, organ_code, metric_type, metric_ref)
VALUES ('TPL_PELVIS', 'BLADDER', '膀胱', 'V_DOSE_PCT', 4000, 40.00);
