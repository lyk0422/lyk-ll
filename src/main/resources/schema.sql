-- 放射性药物活度校准与分装核对服务建表脚本（H2 MySQL 兼容模式 / MySQL 均可执行）
-- 所有时刻字段一律为 UTC epoch 秒（BIGINT），时区含义：UTC；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS nuclide (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '核素编码（合成数据），唯一',
    name VARCHAR(64) NOT NULL COMMENT '核素名称（合成数据）',
    half_life_seconds BIGINT NOT NULL COMMENT '半衰期，单位：秒；衰变计算唯一参数来源',
    CONSTRAINT uk_nuclide_code UNIQUE (code)
) COMMENT='合成核素表';

CREATE TABLE IF NOT EXISTS dose_calibrator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '活度计编码（合成数据），唯一',
    name VARCHAR(64) NOT NULL COMMENT '活度计名称（合成数据）',
    created_at_epoch BIGINT NOT NULL COMMENT '登记时刻，UTC epoch 秒',
    CONSTRAINT uk_calibrator_code UNIQUE (code)
) COMMENT='活度计台账';

CREATE TABLE IF NOT EXISTS calibrator_daily_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    calibrator_id BIGINT NOT NULL COMMENT '活度计主键',
    check_date DATE NOT NULL COMMENT '日检日期（UTC 日历日）',
    reference_activity_bq DOUBLE NOT NULL COMMENT '参考源标称活度，单位：Bq（合成数据）',
    measured_activity_bq DOUBLE NOT NULL COMMENT '实测活度，单位：Bq',
    deviation_ratio DOUBLE NOT NULL COMMENT '相对偏差=(实测-标称)/标称；绝对值>0.05 当日禁用，恰 0.05 合法',
    passed BOOLEAN NOT NULL COMMENT '是否通过：TRUE 当日可用，FALSE 当日禁用',
    created_at_epoch BIGINT NOT NULL COMMENT '记录时刻，UTC epoch 秒',
    CONSTRAINT uk_daily_check_request UNIQUE (request_id),
    CONSTRAINT uk_daily_check_session UNIQUE (calibrator_id, check_date),
    CONSTRAINT fk_daily_check_calibrator FOREIGN KEY (calibrator_id) REFERENCES dose_calibrator (id)
) COMMENT='活度计日检记录；同一活度计同一日期仅允许一个校准会话';

CREATE TABLE IF NOT EXISTS mother_liquor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_code VARCHAR(64) NOT NULL COMMENT '母液批次号（合成数据），唯一',
    nuclide_id BIGINT NOT NULL COMMENT '核素主键',
    initial_activity_bq DOUBLE NOT NULL COMMENT '初始活度，单位：Bq，对应 reference_time_epoch 时刻',
    reference_time_epoch BIGINT NOT NULL COMMENT '初始活度标定时刻，UTC epoch 秒',
    remaining_activity_bq DOUBLE NOT NULL COMMENT '当前剩余活度，单位：Bq，对应 remaining_time_epoch 时刻；发药扣减后更新',
    remaining_time_epoch BIGINT NOT NULL COMMENT '剩余活度对应时刻，UTC epoch 秒',
    status VARCHAR(16) NOT NULL COMMENT '批次状态：OPEN 在库 / CLOSED 已完结',
    created_at_epoch BIGINT NOT NULL COMMENT '登记时刻，UTC epoch 秒',
    CONSTRAINT uk_mother_liquor_batch UNIQUE (batch_code),
    CONSTRAINT fk_mother_liquor_nuclide FOREIGN KEY (nuclide_id) REFERENCES nuclide (id)
) COMMENT='母液批次台账';

CREATE TABLE IF NOT EXISTS prescription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '处方编号（合成数据），唯一',
    nuclide_id BIGINT NOT NULL COMMENT '核素主键',
    prescribed_activity_bq DOUBLE NOT NULL COMMENT '处方活度，单位：Bq，对应给药时刻',
    administration_time_epoch BIGINT NOT NULL COMMENT '给药时刻，UTC epoch 秒',
    created_at_epoch BIGINT NOT NULL COMMENT '登记时刻，UTC epoch 秒',
    CONSTRAINT uk_prescription_code UNIQUE (code),
    CONSTRAINT fk_prescription_nuclide FOREIGN KEY (nuclide_id) REFERENCES nuclide (id)
) COMMENT='处方台账';

CREATE TABLE IF NOT EXISTS dispensing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '分装计算幂等请求标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '分装计算幂等指纹',
    mother_liquor_id BIGINT NOT NULL COMMENT '母液批次主键',
    prescription_id BIGINT NOT NULL COMMENT '处方主键',
    calibrator_id BIGINT NOT NULL COMMENT '分装所用活度计主键',
    dispensed_activity_bq DOUBLE COMMENT '分装活度（推算至给药时刻），单位：Bq；被拒绝时为 NULL',
    deviation_ratio DOUBLE COMMENT '与处方活度的相对偏差；绝对值>0.10 拒绝分装，恰 0.10 合法',
    status VARCHAR(16) NOT NULL COMMENT '状态：CALCULATED 待核对 / CHECKED 双人核对完成 / DISPENSED 发药完成(终态) / REJECTED 已拒绝(终态)',
    first_checker VARCHAR(64) COMMENT '第一名核对人员；NULL 表示尚未核对',
    second_checker VARCHAR(64) COMMENT '第二名核对人员；须与第一名不同；NULL 表示未完成双人核对',
    dispense_request_id VARCHAR(64) COMMENT '发药幂等请求标识；NULL 表示尚未发药',
    dispense_fingerprint VARCHAR(64) COMMENT '发药幂等指纹；NULL 表示尚未发药',
    dispensed_at_epoch BIGINT COMMENT '发药完成时刻，UTC epoch 秒；NULL 表示未发药',
    created_at_epoch BIGINT NOT NULL COMMENT '创建时刻，UTC epoch 秒',
    updated_at_epoch BIGINT NOT NULL COMMENT '最近更新时刻，UTC epoch 秒',
    CONSTRAINT uk_dispensing_request UNIQUE (request_id),
    CONSTRAINT uk_dispensing_dispense_request UNIQUE (dispense_request_id),
    CONSTRAINT fk_dispensing_mother FOREIGN KEY (mother_liquor_id) REFERENCES mother_liquor (id),
    CONSTRAINT fk_dispensing_prescription FOREIGN KEY (prescription_id) REFERENCES prescription (id),
    CONSTRAINT fk_dispensing_calibrator FOREIGN KEY (calibrator_id) REFERENCES dose_calibrator (id)
) COMMENT='分装批次；发药完成为终态';

CREATE TABLE IF NOT EXISTS dispensing_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispensing_id BIGINT NOT NULL COMMENT '分装批次主键',
    checker VARCHAR(64) NOT NULL COMMENT '核对人员标识（合成数据）',
    check_seq INT NOT NULL COMMENT '核对次序：1 第一人 / 2 第二人',
    checked_at_epoch BIGINT NOT NULL COMMENT '核对时刻，UTC epoch 秒',
    CONSTRAINT uk_dispensing_check_seq UNIQUE (dispensing_id, check_seq),
    CONSTRAINT fk_check_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id)
) COMMENT='双人核对链，可按次序复算';

CREATE TABLE IF NOT EXISTS reconciliation_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispensing_id BIGINT NOT NULL COMMENT '分装批次主键',
    initial_activity_bq DOUBLE NOT NULL COMMENT '母液初始活度，单位：Bq',
    dispensed_total_bq DOUBLE NOT NULL COMMENT '分装总量（推算至测量时刻），单位：Bq',
    remaining_activity_bq DOUBLE NOT NULL COMMENT '剩余活度（测量值），单位：Bq',
    decay_residual_bq DOUBLE NOT NULL COMMENT '衰变残量=初始活度-初始活度衰变至测量时刻，单位：Bq',
    balanced BOOLEAN NOT NULL COMMENT '勾稽是否平衡：初始=分装总量+剩余+衰变残量',
    measured_at_epoch BIGINT NOT NULL COMMENT '剩余活度测量时刻，UTC epoch 秒',
    created_at_epoch BIGINT NOT NULL COMMENT '台账记录时刻，UTC epoch 秒',
    CONSTRAINT uk_ledger_dispensing UNIQUE (dispensing_id),
    CONSTRAINT fk_ledger_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id)
) COMMENT='发药勾稽台账，可按衰变公式复算';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispensing_id BIGINT NOT NULL COMMENT '分装批次主键',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型，如 LATE_MEASUREMENT',
    payload VARCHAR(512) NOT NULL COMMENT '迟到数据内容（合成数据）',
    received_at_epoch BIGINT NOT NULL COMMENT '接收时刻，UTC epoch 秒',
    CONSTRAINT fk_late_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id)
) COMMENT='批次完成后的迟到事件台账，仅记录不改动业务状态';
