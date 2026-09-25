-- 放疗计划剂量审核与分次执行核对服务建表脚本（H2 MySQL 兼容模式，合成数据）
-- 时间列均存储 UTC 时间戳；剂量单位均为 Gy；空值含义见各列注释。

CREATE TABLE patient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_no VARCHAR(64) NOT NULL,
    patient_name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_patient_no UNIQUE (patient_no)
);
COMMENT ON TABLE patient IS '患者表（合成数据）';
COMMENT ON COLUMN patient.patient_no IS '患者编号，业务唯一';
COMMENT ON COLUMN patient.patient_name IS '患者姓名（合成）';
COMMENT ON COLUMN patient.created_at IS '创建时间，UTC';

CREATE TABLE plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id BIGINT NOT NULL,
    version INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    prescription_total_gy DECIMAL(10,2) NOT NULL,
    fraction_count INT NOT NULL,
    fraction_dose_gy DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_plan_patient_version UNIQUE (patient_id, version)
);
COMMENT ON TABLE plan IS '放疗计划表，按患者+版本唯一';
COMMENT ON COLUMN plan.patient_id IS '患者ID，关联 patient.id';
COMMENT ON COLUMN plan.version IS '计划版本号，从1开始递增，换版产生新版本';
COMMENT ON COLUMN plan.status IS '状态机：DESIGN设计/PHYSICS_REVIEWED物理审核通过/APPROVED医师批准/VERIFIED首次验证通过/EXECUTING分次执行中/CLOSED疗程结案(终态)/INVALIDATED已失效(被新版替代)';
COMMENT ON COLUMN plan.prescription_total_gy IS '处方总剂量，单位Gy，须等于分次剂量×次数';
COMMENT ON COLUMN plan.fraction_count IS '分次次数';
COMMENT ON COLUMN plan.fraction_dose_gy IS '单次分次剂量，单位Gy';
COMMENT ON COLUMN plan.created_at IS '创建时间，UTC';
COMMENT ON COLUMN plan.updated_at IS '最近状态变更时间，UTC';

CREATE TABLE oar_constraint_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    organ VARCHAR(64) NOT NULL,
    max_dose_gy DECIMAL(10,2) NOT NULL,
    CONSTRAINT uk_oar_organ UNIQUE (organ)
);
COMMENT ON TABLE oar_constraint_template IS '危及器官剂量约束模板（合成），恰等限值合法，超限拒绝';
COMMENT ON COLUMN oar_constraint_template.organ IS '危及器官名称';
COMMENT ON COLUMN oar_constraint_template.max_dose_gy IS '剂量限值，单位Gy';

CREATE TABLE plan_oar_dose (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    organ VARCHAR(64) NOT NULL,
    dose_gy DECIMAL(10,2) NOT NULL,
    CONSTRAINT uk_plan_oar UNIQUE (plan_id, organ)
);
COMMENT ON TABLE plan_oar_dose IS '计划危及器官剂量体积明细，审核时逐项对照模板校验，可复算';
COMMENT ON COLUMN plan_oar_dose.plan_id IS '计划ID';
COMMENT ON COLUMN plan_oar_dose.organ IS '危及器官名称';
COMMENT ON COLUMN plan_oar_dose.dose_gy IS '计划剂量，单位Gy';

CREATE TABLE review_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    reviewer VARCHAR(64) NOT NULL,
    result VARCHAR(10) NOT NULL,
    violations VARCHAR(4000),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_review_request UNIQUE (request_id)
);
COMMENT ON TABLE review_record IS '物理审核记录，request_id 幂等；指纹覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN review_record.request_id IS '幂等请求ID，同键同参返回首次结果，同键异参返回409';
COMMENT ON COLUMN review_record.fingerprint IS 'SHA-256 指纹，覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN review_record.result IS '审核结果：PASSED通过/REJECTED拒绝';
COMMENT ON COLUMN review_record.violations IS '超限明细JSON数组，可复算；通过时为空数组';

CREATE TABLE approval_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    physician VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_approval_request UNIQUE (request_id),
    CONSTRAINT uk_approval_plan UNIQUE (plan_id)
);
COMMENT ON TABLE approval_record IS '医师批准记录，request_id 幂等；plan_id 唯一约束保证并发批准唯一成功';
COMMENT ON COLUMN approval_record.physician IS '批准医师（合成）';

CREATE TABLE verification_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    measured_dose_gy DECIMAL(10,3) NOT NULL,
    deviation_percent DECIMAL(8,4) NOT NULL,
    passed BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_verification_request UNIQUE (request_id)
);
COMMENT ON TABLE verification_record IS '首次模体验证记录链，剂量偏差>3%不得执行，恰3%合法';
COMMENT ON COLUMN verification_record.measured_dose_gy IS '模体实测分次剂量，单位Gy';
COMMENT ON COLUMN verification_record.deviation_percent IS '剂量偏差百分比，相对计划分次剂量';
COMMENT ON COLUMN verification_record.passed IS '是否通过：偏差<=3%为通过';

CREATE TABLE fraction_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    fraction_no INT NOT NULL,
    dose_gy DECIMAL(10,2) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_fraction_request UNIQUE (request_id),
    CONSTRAINT uk_fraction_plan_no UNIQUE (plan_id, fraction_no)
);
COMMENT ON TABLE fraction_record IS '分次执行台账，按患者+计划+分次号去重，可复算';
COMMENT ON COLUMN fraction_record.fraction_no IS '分次号，从1开始顺序执行，跳次拒绝并告警';
COMMENT ON COLUMN fraction_record.dose_gy IS '实际执行分次剂量，单位Gy，须等于计划分次剂量';

CREATE TABLE late_fraction_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    fraction_no INT NOT NULL,
    dose_gy DECIMAL(10,2) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_late_request UNIQUE (request_id)
);
COMMENT ON TABLE late_fraction_event IS '结案后迟到分次事件，只记事件不计入执行台账';

CREATE TABLE closure_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_closure_request UNIQUE (request_id),
    CONSTRAINT uk_closure_plan UNIQUE (plan_id)
);
COMMENT ON TABLE closure_record IS '疗程结案记录，结案为终态，重复结案返回幂等成功';

CREATE TABLE alert_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    alert_type VARCHAR(32) NOT NULL,
    message VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE alert_record IS '告警记录，如跳次执行拒绝告警';
COMMENT ON COLUMN alert_record.alert_type IS '告警类型：FRACTION_SKIP跳次执行';

-- 合成危及器官剂量约束模板
INSERT INTO oar_constraint_template (organ, max_dose_gy) VALUES
    ('SPINAL_CORD', 45.00),
    ('BRAINSTEM', 54.00),
    ('HEART', 30.00),
    ('LUNG_LEFT', 20.00),
    ('PAROTID_LEFT', 26.00);
