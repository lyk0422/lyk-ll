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
    code VARCHAR(64) NOT NULL COMMENT '处方编号（合成数据），唯一；给药时刻变更产生的内部版本编号追加 #v版本号',
    nuclide_id BIGINT NOT NULL COMMENT '核素主键',
    prescribed_activity_bq DOUBLE NOT NULL COMMENT '处方活度，单位：Bq，对应给药时刻；变更给药时刻时沿用不变',
    administration_time_epoch BIGINT NOT NULL COMMENT '给药时刻，UTC epoch 秒；变更单生效时产生带新时刻的新版本行',
    version_no INT NOT NULL DEFAULT 1 COMMENT '处方版本号，从 1 开始；给药时刻变更生效加 1',
    root_prescription_id BIGINT COMMENT '处方族根版本主键；首版本指向自身，给药时刻变更版本共享同一根；NULL 仅见于历史行',
    created_at_epoch BIGINT NOT NULL COMMENT '登记时刻，UTC epoch 秒',
    CONSTRAINT uk_prescription_code UNIQUE (code),
    CONSTRAINT fk_prescription_nuclide FOREIGN KEY (nuclide_id) REFERENCES nuclide (id)
) COMMENT='处方台账；给药时刻变更以新增版本行实现，旧版本保留作为证据';

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
    version_no INT NOT NULL DEFAULT 1 COMMENT '分装当前有效计算版本号，从 1 开始；变更单生效加 1',
    active_change_order_id BIGINT COMMENT '当前挂起的变更单主键；NULL 表示无候选；候选作废或生效后清空',
    created_at_epoch BIGINT NOT NULL COMMENT '创建时刻，UTC epoch 秒',
    updated_at_epoch BIGINT NOT NULL COMMENT '最近更新时刻，UTC epoch 秒',
    CONSTRAINT uk_dispensing_request UNIQUE (request_id),
    CONSTRAINT uk_dispensing_dispense_request UNIQUE (dispense_request_id),
    CONSTRAINT fk_dispensing_mother FOREIGN KEY (mother_liquor_id) REFERENCES mother_liquor (id),
    CONSTRAINT fk_dispensing_prescription FOREIGN KEY (prescription_id) REFERENCES prescription (id),
    CONSTRAINT fk_dispensing_calibrator FOREIGN KEY (calibrator_id) REFERENCES dose_calibrator (id)
) COMMENT='分装批次；发药完成为终态；给药时刻变更以版本化候选单生效，旧计算与签认保留';

CREATE TABLE IF NOT EXISTS dispensing_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispensing_id BIGINT NOT NULL COMMENT '分装批次主键',
    version_no INT NOT NULL COMMENT '计算版本号，从 1 开始；变更生效产生新版本',
    prescription_id BIGINT NOT NULL COMMENT '该版本依据的处方版本主键（含其给药时刻）',
    administration_time_epoch BIGINT NOT NULL COMMENT '该版本给药时刻，UTC epoch 秒',
    dispensed_activity_bq DOUBLE COMMENT '该版本推算至给药时刻的分装活度，单位：Bq；超偏差拒绝时为 NULL',
    deviation_ratio DOUBLE COMMENT '该版本与处方活度的相对偏差；绝对值>0.10 拒绝，恰 0.10 合法',
    calc_status VARCHAR(16) NOT NULL COMMENT '版本计算结果：LEGAL 合法 / REJECTED 超偏差',
    change_order_id BIGINT COMMENT '由哪张变更单产生；NULL 表示初始计算版本',
    created_at_epoch BIGINT NOT NULL COMMENT '版本产生时刻，UTC epoch 秒',
    CONSTRAINT uk_dispensing_version_no UNIQUE (dispensing_id, version_no),
    CONSTRAINT fk_version_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id),
    CONSTRAINT fk_version_prescription FOREIGN KEY (prescription_id) REFERENCES prescription (id)
) COMMENT='分装计算版本证据；初始计算与每次变更的计算均不可变留存';

CREATE TABLE IF NOT EXISTS dispensing_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispensing_id BIGINT NOT NULL COMMENT '分装批次主键',
    version_no INT NOT NULL DEFAULT 1 COMMENT '签认所属计算版本号；变更生效后旧版本签认保留但不再有效',
    checker VARCHAR(64) NOT NULL COMMENT '核对人员标识（合成数据）',
    check_seq INT NOT NULL COMMENT '核对次序：1 第一人 / 2 第二人',
    checked_at_epoch BIGINT NOT NULL COMMENT '核对时刻，UTC epoch 秒',
    CONSTRAINT uk_dispensing_check_seq UNIQUE (dispensing_id, version_no, check_seq),
    CONSTRAINT fk_check_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id)
) COMMENT='双人核对链，按计算版本与次序留存复算；旧版本签认不随版本生效迁移';

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

CREATE TABLE IF NOT EXISTS dispensing_change_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '候选提交幂等请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '提交幂等指纹：结构化（长度前缀）覆盖 requestId、分装、期望版本、处方版本、新给药时刻',
    dispensing_id BIGINT NOT NULL COMMENT '被变更的分装批次主键',
    expected_version INT NOT NULL COMMENT '提交时客户端持有的分装版本，从 1 开始；与库内不一致返回 409',
    prescription_version INT NOT NULL COMMENT '提交时引用的当前处方版本号',
    new_prescription_id BIGINT COMMENT '新给药时刻对应的处方版本主键；仅合法候选创建处方版本，超偏差/库存失败为 NULL',
    new_administration_time_epoch BIGINT NOT NULL COMMENT '候选新给药时刻，UTC epoch 秒；须晚于母液参考时刻',
    candidate_version_no INT NOT NULL COMMENT '候选计算版本号 = 生效后版本号（当前版本 + 1）',
    candidate_activity_bq DOUBLE COMMENT '候选目标活度（按原核素半衰期与原母液快照重算），单位：Bq；超偏差或库存不足为 NULL',
    candidate_deviation_ratio DOUBLE COMMENT '候选活度与处方活度的相对偏差',
    calc_status VARCHAR(16) NOT NULL COMMENT '候选校验结果：LEGAL 合法可复核 / REJECTED 超偏差或库存校验失败（终态，不扣减）',
    status VARCHAR(16) NOT NULL COMMENT '变更单状态：PENDING 待双人复核 / APPROVED 已生效 / VOID 已作废 / SUPERSEDED 因原版本先发药而失效（均终态）',
    first_reviewer VARCHAR(64) COMMENT '第一名复核人员；NULL 表示尚未复核',
    second_reviewer VARCHAR(64) COMMENT '第二名复核人员；须与第一名不同；NULL 表示未完成双人复核',
    approved_at_epoch BIGINT COMMENT '生效时刻，UTC epoch 秒；NULL 表示未生效',
    voided_at_epoch BIGINT COMMENT '作废时刻，UTC epoch 秒；NULL 表示未作废',
    void_request_id VARCHAR(64) COMMENT '作废操作幂等请求标识；NULL 表示未作废',
    created_at_epoch BIGINT NOT NULL COMMENT '候选提交时刻，UTC epoch 秒',
    updated_at_epoch BIGINT NOT NULL COMMENT '最近更新时刻，UTC epoch 秒',
    CONSTRAINT uk_change_submit_request UNIQUE (request_id),
    CONSTRAINT uk_change_void_request UNIQUE (void_request_id),
    CONSTRAINT fk_change_dispensing FOREIGN KEY (dispensing_id) REFERENCES dispensing (id),
    CONSTRAINT fk_change_new_prescription FOREIGN KEY (new_prescription_id) REFERENCES prescription (id)
) COMMENT='给药时刻变更候选单；形成候选不立即改有效分装，双人复核通过才生效，旧计算与签认保留';

CREATE TABLE IF NOT EXISTS dispensing_change_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_order_id BIGINT NOT NULL COMMENT '变更单主键',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人员标识（合成数据）',
    review_seq INT NOT NULL COMMENT '复核次序：1 第一人 / 2 第二人',
    reviewed_at_epoch BIGINT NOT NULL COMMENT '复核时刻，UTC epoch 秒',
    CONSTRAINT uk_change_check_seq UNIQUE (change_order_id, review_seq),
    CONSTRAINT fk_change_check_order FOREIGN KEY (change_order_id) REFERENCES dispensing_change_order (id)
) COMMENT='变更单双人复核链，按次序留存，两名复核人员必须不同';
