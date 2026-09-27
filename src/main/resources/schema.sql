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
    prescription_id BIGINT NOT NULL COMMENT '处方主键（当前处方版本引用）',
    calibrator_id BIGINT NOT NULL COMMENT '分装所用活度计主键',
    version INT NOT NULL COMMENT '当前有效计算版本号，从 1 开始；每次变更生效加 1',
    administration_time_epoch BIGINT NOT NULL COMMENT '当前有效版本给药时刻，UTC epoch 秒；变更生效同事务替换',
    dispensed_activity_bq DOUBLE COMMENT '当前有效版本分装活度（推算至给药时刻），单位：Bq；被拒绝时为 NULL',
    deviation_ratio DOUBLE COMMENT '当前有效版本与处方活度的相对偏差；绝对值>0.10 拒绝分装，恰 0.10 合法',
    status VARCHAR(16) NOT NULL COMMENT '状态：CALCULATED 待核对 / CHECKED 双人核对完成 / DISPENSED 发药完成(终态) / REJECTED 已拒绝(终态)',
    first_checker VARCHAR(64) COMMENT '当前版本第一名核对人员；NULL 表示尚未核对；变更生效后清空',
    second_checker VARCHAR(64) COMMENT '当前版本第二名核对人员；须与第一名不同；变更生效后清空',
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
    version INT NOT NULL COMMENT '签认所属计算版本号；旧版本签认随版本永久保留，但对新版本不再有效',
    checker VARCHAR(64) NOT NULL COMMENT '核对人员标识（合成数据）',
    check_seq INT NOT NULL COMMENT '核对次序：1 第一人 / 2 第二人',
    checked_at_epoch BIGINT NOT NULL COMMENT '核对时刻，UTC epoch 秒',
    CONSTRAINT uk_dispensing_check_seq UNIQUE (dispensing_id, version, check_seq),
    CONSTRAINT fk_check_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id)
) COMMENT='双人核对链，按版本与次序保留；变更生效后旧签认失效但不删除';

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

CREATE TABLE IF NOT EXISTS dispensing_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispensing_id BIGINT NOT NULL COMMENT '分装批次主键',
    version INT NOT NULL COMMENT '计算版本号，从 1 开始；变更生效新增版本',
    administration_time_epoch BIGINT NOT NULL COMMENT '该版本给药时刻，UTC epoch 秒',
    dispensed_activity_bq DOUBLE COMMENT '该版本分装活度（推算至给药时刻），单位：Bq；初算拒绝时为 NULL',
    deviation_ratio DOUBLE COMMENT '该版本与处方活度的相对偏差；绝对值>0.10 拒绝，恰 0.10 合法',
    mother_liquor_id BIGINT NOT NULL COMMENT '计算所用母液批次主键（母液快照来源）',
    mother_initial_activity_bq DOUBLE NOT NULL COMMENT '母液初始活度快照，单位：Bq',
    mother_reference_time_epoch BIGINT NOT NULL COMMENT '母液参考时刻快照，UTC epoch 秒',
    half_life_seconds BIGINT NOT NULL COMMENT '合成核素半衰期快照，单位：秒',
    source VARCHAR(16) NOT NULL COMMENT '来源：INITIAL 初算 / CHANGE 给药时刻变更',
    change_order_id BIGINT COMMENT '来源变更单主键；INITIAL 时为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '版本状态：ACTIVE 当前有效 / SUPERSEDED 已被变更替代（保留证据） / REJECTED 初算拒绝',
    created_at_epoch BIGINT NOT NULL COMMENT '版本创建时刻，UTC epoch 秒',
    CONSTRAINT uk_dispensing_version_seq UNIQUE (dispensing_id, version),
    CONSTRAINT fk_version_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id)
) COMMENT='分装计算版本证据；旧版本永久保留，可按衰变公式复算';

CREATE TABLE IF NOT EXISTS dose_change_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '候选提交幂等请求标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '提交幂等指纹（结构化无歧义）',
    dispensing_id BIGINT NOT NULL COMMENT '被变更分装批次主键',
    expected_version INT NOT NULL COMMENT '提交时携带的期望分装版本，从 1 开始；与当前版本不一致 409',
    prescription_id BIGINT NOT NULL COMMENT '提交时当前处方版本（处方主键快照）',
    new_administration_time_epoch BIGINT NOT NULL COMMENT '新 UTC 给药时刻，须晚于母液参考时刻',
    candidate_version INT NOT NULL COMMENT '候选计算版本号=期望版本+1；生效后成为新版本',
    candidate_activity_bq DOUBLE NOT NULL COMMENT '按原半衰期与原母液快照重算的目标活度，单位：Bq',
    candidate_deviation_ratio DOUBLE NOT NULL COMMENT '候选活度与处方活度相对偏差；须在 ±10% 内',
    mother_initial_activity_bq DOUBLE NOT NULL COMMENT '母液初始活度快照，单位：Bq',
    mother_reference_time_epoch BIGINT NOT NULL COMMENT '母液参考时刻快照，UTC epoch 秒',
    half_life_seconds BIGINT NOT NULL COMMENT '合成核素半衰期快照，单位：秒',
    inventory_available_bq DOUBLE NOT NULL COMMENT '提交时库存校验可用活度（母液剩余推算至新给药时刻），单位：Bq',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待双人复核 / APPROVED 已生效 / REJECTED 因发药先成被拒 / CANCELLED 已作废',
    pending_dispensing_id BIGINT COMMENT 'PENDING 时等于 dispensing_id，其余状态为 NULL；唯一索引保证一批至多一个待决候选',
    first_reviewer VARCHAR(64) COMMENT '第一名复核人员；NULL 表示尚未复核',
    second_reviewer VARCHAR(64) COMMENT '第二名复核人员；须与第一名不同；两签齐备同事务生效',
    submitted_at_epoch BIGINT NOT NULL COMMENT '候选提交时刻，UTC epoch 秒',
    decided_at_epoch BIGINT COMMENT '终局（生效/拒绝/作废）时刻，UTC epoch 秒；NULL 表示待决',
    CONSTRAINT uk_change_order_request UNIQUE (request_id),
    CONSTRAINT uk_change_order_pending UNIQUE (pending_dispensing_id),
    CONSTRAINT fk_change_order_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id)
) COMMENT='未发药分装给药时刻变更候选单；生效前不修改有效分装';

CREATE TABLE IF NOT EXISTS dose_change_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_order_id BIGINT NOT NULL COMMENT '变更单主键',
    checker VARCHAR(64) NOT NULL COMMENT '复核人员标识（合成数据）',
    check_seq INT NOT NULL COMMENT '复核次序：1 第一人 / 2 第二人（第二签触发生效）',
    checked_at_epoch BIGINT NOT NULL COMMENT '复核时刻，UTC epoch 秒',
    CONSTRAINT uk_change_check_seq UNIQUE (change_order_id, check_seq),
    CONSTRAINT fk_change_check_order FOREIGN KEY (change_order_id) REFERENCES dose_change_order (id)
) COMMENT='变更候选双人复核链，两名复核人员必须不同';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求标识，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '结构化无歧义指纹：操作、版本、集合、精确时刻等完整参数',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：SUBMIT_CHANGE / REVIEW_CHANGE / CANCEL_CHANGE',
    result_snapshot VARCHAR(4000) NOT NULL COMMENT '首次成功结果冻结快照（JSON）；同键同参重放直接返回',
    created_at_epoch BIGINT NOT NULL COMMENT '首次成功提交时刻，UTC epoch 秒'
) COMMENT='新增写操作幂等冻结记录；业务、版本与幂等结果同事务，失败不占键';
