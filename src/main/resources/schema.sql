-- 本地 H2（MODE=MySQL）自动建表脚本；仅在 JVM 生命周期内保留数据。
-- 列注释使用 H2 标准语法 COMMENT ON COLUMN。

CREATE TABLE IF NOT EXISTS ingredient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN ingredient.code IS '原料编码（合成字典）';
COMMENT ON COLUMN ingredient.name IS '原料名称';

CREATE TABLE IF NOT EXISTS drug_additive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    max_ratio DECIMAL(9,6) NOT NULL,
    withdrawal_days INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN drug_additive.code IS '药物添加剂编码（合成字典）';
COMMENT ON COLUMN drug_additive.max_ratio IS '最大添加比例（占配方质量比，0~1）；恰等于上限合法，超过拒绝';
COMMENT ON COLUMN drug_additive.withdrawal_days IS '该药物休药天数（按 UTC 日历日计）';
COMMENT ON COLUMN drug_additive.active IS '字典是否有效';

CREATE TABLE IF NOT EXISTS drug_allowed_species (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    drug_code VARCHAR(64) NOT NULL,
    species VARCHAR(64) NOT NULL,
    UNIQUE (drug_code, species)
);
COMMENT ON COLUMN drug_allowed_species.drug_code IS '药物添加剂编码';
COMMENT ON COLUMN drug_allowed_species.species IS '允许使用的目标动物种类';

CREATE TABLE IF NOT EXISTS contraindication (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    drug_a_code VARCHAR(64) NOT NULL,
    drug_b_code VARCHAR(64) NOT NULL,
    UNIQUE (drug_a_code, drug_b_code)
);
COMMENT ON COLUMN contraindication.drug_a_code IS '禁忌药物对 A（无序对，按编码字典序存放较小者）';
COMMENT ON COLUMN contraindication.drug_b_code IS '禁忌药物对 B（无序对，按编码字典序存放较大者）';

CREATE TABLE IF NOT EXISTS herd_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL UNIQUE,
    species VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'FEEDING',
    last_medicated_feed_at_utc TIMESTAMP(3),
    withdrawal_until_date DATE,
    governing_withdrawal_days INT,
    slaughtered_at_utc TIMESTAMP(3),
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN herd_batch.batch_no IS '养殖批次号（合成数据）';
COMMENT ON COLUMN herd_batch.species IS '批次动物种类';
COMMENT ON COLUMN herd_batch.status IS '批次状态：FEEDING 饲养中；SLAUGHTERED 已出栏（终态）';
COMMENT ON COLUMN herd_batch.last_medicated_feed_at_utc IS '合并口径下最晚一次含药饲喂时刻（UTC）；从未饲喂含药饲料为 NULL';
COMMENT ON COLUMN herd_batch.withdrawal_until_date IS '休药期届满日（UTC 日历日）；该日 24:00（即次日 00:00 UTC）后方可出栏；NULL 表示无休药义务';
COMMENT ON COLUMN herd_batch.governing_withdrawal_days IS '当前合并休药期的决定性最大休药天数（单位：UTC 日）；NULL 无休药义务';
COMMENT ON COLUMN herd_batch.slaughtered_at_utc IS '实际出栏时刻（UTC）；NULL 未出栏';

CREATE TABLE IF NOT EXISTS feed_formula (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    name VARCHAR(128) NOT NULL,
    target_species VARCHAR(64),
    contains_drug BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL,
    reject_reason VARCHAR(1024),
    submit_request_id VARCHAR(64),
    fingerprint VARCHAR(128),
    approved_at_utc TIMESTAMP(3),
    first_produced_at_utc TIMESTAMP(3),
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (formula_code, version),
    UNIQUE (submit_request_id)
);
COMMENT ON COLUMN feed_formula.formula_code IS '配方业务编码；同一编码可有多个独立校验的版本';
COMMENT ON COLUMN feed_formula.version IS '配方版本号，从 1 递增；批准后冻结';
COMMENT ON COLUMN feed_formula.target_species IS '目标动物种类；含药配方必填且须与各药物允许种类匹配，无药配方可空';
COMMENT ON COLUMN feed_formula.contains_drug IS '是否含药物添加剂';
COMMENT ON COLUMN feed_formula.status IS 'SUBMITTED 已提交待批准 / REJECTED 合规校验拒绝 / APPROVED 已批准冻结 / IN_PRODUCTION 已投产';
COMMENT ON COLUMN feed_formula.reject_reason IS '合规校验拒绝原因；通过为 NULL';
COMMENT ON COLUMN feed_formula.submit_request_id IS '提交幂等键 requestId';
COMMENT ON COLUMN feed_formula.fingerprint IS '提交指纹（SHA-256，覆盖 requestId 与全部业务字段）';
COMMENT ON COLUMN feed_formula.approved_at_utc IS '批准时刻（UTC），批准即冻结';
COMMENT ON COLUMN feed_formula.first_produced_at_utc IS '首次投产时刻（UTC）';

CREATE TABLE IF NOT EXISTS formula_ingredient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_id BIGINT NOT NULL,
    ingredient_code VARCHAR(64) NOT NULL,
    ratio DECIMAL(9,6) NOT NULL,
    UNIQUE (formula_id, ingredient_code)
);
COMMENT ON COLUMN formula_ingredient.formula_id IS '配方主键';
COMMENT ON COLUMN formula_ingredient.ingredient_code IS '原料编码';
COMMENT ON COLUMN formula_ingredient.ratio IS '原料添加比例（质量占比，0~1）';

CREATE TABLE IF NOT EXISTS formula_drug (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_id BIGINT NOT NULL,
    drug_code VARCHAR(64) NOT NULL,
    ratio DECIMAL(9,6) NOT NULL,
    UNIQUE (formula_id, drug_code)
);
COMMENT ON COLUMN formula_drug.formula_id IS '配方主键';
COMMENT ON COLUMN formula_drug.drug_code IS '药物添加剂编码';
COMMENT ON COLUMN formula_drug.ratio IS '药物添加比例（质量占比，0~1）；不得超过字典上限，恰等于合法';

CREATE TABLE IF NOT EXISTS production_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    formula_id BIGINT NOT NULL,
    formula_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    produced_at_utc TIMESTAMP(3) NOT NULL,
    UNIQUE (batch_id, formula_id)
);
COMMENT ON TABLE production_record IS '投产记录：某批准版本配方对某养殖批次投产；同批次并发投产不同配方各持一行';
COMMENT ON COLUMN production_record.batch_id IS '养殖批次主键';
COMMENT ON COLUMN production_record.formula_id IS '投产时已批准且冻结的配方版本主键';
COMMENT ON COLUMN production_record.produced_at_utc IS '投产时刻（UTC）；休药义务以该时刻已批准版本为准，后续版本不追溯';

CREATE TABLE IF NOT EXISTS production_drug_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    production_id BIGINT NOT NULL,
    drug_code VARCHAR(64) NOT NULL,
    ratio DECIMAL(9,6) NOT NULL,
    withdrawal_days INT NOT NULL,
    UNIQUE (production_id, drug_code)
);
COMMENT ON TABLE production_drug_snapshot IS '投产时刻冻结的药物清单快照（比例与休药天数），后续配方版本修改不追溯减轻';
COMMENT ON COLUMN production_drug_snapshot.withdrawal_days IS '投产时字典中的休药天数快照（UTC 日）';

CREATE TABLE IF NOT EXISTS feeding_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    production_id BIGINT NOT NULL,
    formula_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    feed_at_utc TIMESTAMP(3) NOT NULL,
    late BOOLEAN NOT NULL DEFAULT FALSE,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE feeding_record IS '含药饲喂事件；出栏后迟到的饲喂只记事件并告警，不改变出栏终态';
COMMENT ON COLUMN feeding_record.batch_id IS '养殖批次主键';
COMMENT ON COLUMN feeding_record.production_id IS '对应投产记录（携带批准版本快照）';
COMMENT ON COLUMN feeding_record.feed_at_utc IS '本次含药饲喂时刻（UTC），即休药期起算点';
COMMENT ON COLUMN feeding_record.late IS '是否为出栏后迟到的饲喂记录';

CREATE TABLE IF NOT EXISTS feeding_record_drug (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feeding_id BIGINT NOT NULL,
    drug_code VARCHAR(64) NOT NULL,
    withdrawal_days INT NOT NULL,
    UNIQUE (feeding_id, drug_code)
);
COMMENT ON TABLE feeding_record_drug IS '单次含药饲喂涉及的药物及休药天数快照，供休药期按药物逐支复算';

CREATE TABLE IF NOT EXISTS compliance_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_type VARCHAR(48) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    feeding_id BIGINT,
    detail VARCHAR(1024) NOT NULL,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN compliance_alert.alert_type IS '告警类型：LATE_MEDICATED_FEEDING 出栏后迟到含药饲喂';
COMMENT ON COLUMN compliance_alert.batch_no IS '涉事养殖批次号';
COMMENT ON COLUMN compliance_alert.feeding_id IS '关联迟到饲喂记录主键；无则 NULL';
COMMENT ON COLUMN compliance_alert.detail IS '告警详情';

CREATE TABLE IF NOT EXISTS vet_drug_catalog (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL UNIQUE,
    withdrawal_days INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE vet_drug_catalog IS '治疗性兽药目录（合成数据）；直报按药品名称受理，目录外拒绝';
COMMENT ON COLUMN vet_drug_catalog.code IS '兽药编码（合成字典）';
COMMENT ON COLUMN vet_drug_catalog.name IS '兽药名称（直报受理口径，唯一）';
COMMENT ON COLUMN vet_drug_catalog.withdrawal_days IS '该兽药休药天数（按 UTC 日历日计）';
COMMENT ON COLUMN vet_drug_catalog.active IS '目录是否有效；停用视同目录外';

CREATE TABLE IF NOT EXISTS treatment_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    batch_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    drug_code VARCHAR(64) NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    withdrawal_days INT NOT NULL,
    administered_at_utc TIMESTAMP(3) NOT NULL,
    confirmed_by1 VARCHAR(64) NOT NULL,
    confirmed_by2 VARCHAR(64) NOT NULL,
    late BOOLEAN NOT NULL DEFAULT FALSE,
    fingerprint VARCHAR(128) NOT NULL,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id, administered_at_utc)
);
COMMENT ON TABLE treatment_report IS '治疗性用药直报；同一批次同一给药时刻只允许一条进行中直报（唯一约束）';
COMMENT ON COLUMN treatment_report.request_id IS '直报幂等键 requestId';
COMMENT ON COLUMN treatment_report.batch_id IS '养殖批次主键';
COMMENT ON COLUMN treatment_report.batch_no IS '养殖批次号（冗余便于查询）';
COMMENT ON COLUMN treatment_report.drug_code IS '兽药目录编码快照';
COMMENT ON COLUMN treatment_report.drug_name IS '兽药名称快照（受理时目录内名称）';
COMMENT ON COLUMN treatment_report.withdrawal_days IS '受理时目录休药天数快照（UTC 日），后续目录修改不追溯';
COMMENT ON COLUMN treatment_report.administered_at_utc IS '本次治疗给药时刻（UTC），即休药期重算起算点之一';
COMMENT ON COLUMN treatment_report.confirmed_by1 IS '第一名确认人（养殖端人员标识）';
COMMENT ON COLUMN treatment_report.confirmed_by2 IS '第二名确认人（养殖端人员标识），须与第一人不同';
COMMENT ON COLUMN treatment_report.late IS '是否为出栏核销（终态）后迟到的直报；迟到只记事件并告警';
COMMENT ON COLUMN treatment_report.fingerprint IS 'SHA-256 指纹，覆盖 requestId 与全部业务字段';

CREATE TABLE IF NOT EXISTS slaughter_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    batch_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    apply_at_utc TIMESTAMP(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE slaughter_application IS '已批准出栏申请台账；被治疗直报撤销的申请状态置为 REVOKED 保留可查';
COMMENT ON COLUMN slaughter_application.request_id IS '出栏申请幂等键 requestId';
COMMENT ON COLUMN slaughter_application.batch_id IS '养殖批次主键';
COMMENT ON COLUMN slaughter_application.batch_no IS '养殖批次号（冗余便于查询）';
COMMENT ON COLUMN slaughter_application.apply_at_utc IS '批准的计划出栏时刻（UTC）';
COMMENT ON COLUMN slaughter_application.status IS 'APPROVED 已批准 / REVOKED 已被治疗直报重算撤销';

CREATE TABLE IF NOT EXISTS slaughter_revocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    batch_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    revoked_slaughter_at_utc TIMESTAMP(3) NOT NULL,
    new_earliest_slaughter_utc TIMESTAMP(3) NOT NULL,
    treatment_report_id BIGINT NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE slaughter_revocation IS '出栏申请撤销记录（审计）：治疗直报重算后与已批准申请冲突时同事务写入，保留可查';
COMMENT ON COLUMN slaughter_revocation.application_id IS '被撤销的出栏申请台账主键';
COMMENT ON COLUMN slaughter_revocation.request_id IS '被撤销出栏申请的原 requestId';
COMMENT ON COLUMN slaughter_revocation.batch_id IS '养殖批次主键';
COMMENT ON COLUMN slaughter_revocation.batch_no IS '养殖批次号（冗余便于查询）';
COMMENT ON COLUMN slaughter_revocation.revoked_slaughter_at_utc IS '被撤销的原计划出栏时刻（UTC）';
COMMENT ON COLUMN slaughter_revocation.new_earliest_slaughter_utc IS '重算后的新最早合法出栏时刻（UTC）';
COMMENT ON COLUMN slaughter_revocation.treatment_report_id IS '触发撤销的治疗直报主键';
COMMENT ON COLUMN slaughter_revocation.reason IS '撤销原因说明';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) PRIMARY KEY,
    request_kind VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_json CLOB NOT NULL,
    created_at_utc TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE idempotent_request IS 'requestId 幂等表：同键同参返回首次结果，同键异参返回 409 且不改变状态';
COMMENT ON COLUMN idempotent_request.request_kind IS '请求类型：FORMULA_SUBMIT 配方提交 / SLAUGHTER_APPLY 出栏申请 / TREATMENT_REPORT 治疗用药直报';
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256 指纹，覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotent_request.response_json IS '首次响应内容快照（JSON）';
