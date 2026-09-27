-- 集中供热二网水力平衡调节服务建表脚本（H2，MODE=MySQL）
-- 所有时间字段均为 UTC 时间戳；金额/温度/开度单位见字段注释。

CREATE TABLE IF NOT EXISTS heat_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    CONSTRAINT uk_heat_station_code UNIQUE (code)
);
COMMENT ON TABLE heat_station IS '换热站';
COMMENT ON COLUMN heat_station.id IS '主键';
COMMENT ON COLUMN heat_station.code IS '换热站编码，全局唯一';
COMMENT ON COLUMN heat_station.name IS '换热站名称';

CREATE TABLE IF NOT EXISTS heating_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL REFERENCES heat_station (id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    CONSTRAINT uk_heating_unit_code UNIQUE (station_id, code)
);
COMMENT ON TABLE heating_unit IS '换热站下属供热单元';
COMMENT ON COLUMN heating_unit.id IS '主键';
COMMENT ON COLUMN heating_unit.station_id IS '所属换热站 ID';
COMMENT ON COLUMN heating_unit.code IS '单元编码，站内唯一';
COMMENT ON COLUMN heating_unit.name IS '单元名称';

CREATE TABLE IF NOT EXISTS valve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_id BIGINT NOT NULL REFERENCES heating_unit (id),
    opening_percent DECIMAL(5,2) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_valve_unit UNIQUE (unit_id),
    CONSTRAINT ck_valve_opening CHECK (opening_percent >= 0 AND opening_percent <= 100)
);
COMMENT ON TABLE valve IS '单元调节阀门，每单元一个，记录当前开度';
COMMENT ON COLUMN valve.id IS '主键';
COMMENT ON COLUMN valve.unit_id IS '所属单元 ID，唯一';
COMMENT ON COLUMN valve.opening_percent IS '当前阀门开度，单位 %，取值 0~100';
COMMENT ON COLUMN valve.updated_at IS '开度最后修改时间（UTC）';

CREATE TABLE IF NOT EXISTS adjustment_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL REFERENCES heat_station (id),
    unit_id BIGINT NOT NULL REFERENCES heating_unit (id),
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    classification VARCHAR(16),
    step_percent DECIMAL(5,2),
    old_opening DECIMAL(5,2),
    new_opening DECIMAL(5,2),
    status VARCHAR(16) NOT NULL,
    executed_at TIMESTAMP,
    assessed_at TIMESTAMP,
    archived_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_adjustment_task_request UNIQUE (request_id)
);
COMMENT ON TABLE adjustment_task IS '调节任务：初调节/再调节指令的受理与流转记录';
COMMENT ON COLUMN adjustment_task.id IS '主键';
COMMENT ON COLUMN adjustment_task.station_id IS '目标换热站 ID';
COMMENT ON COLUMN adjustment_task.unit_id IS '目标单元 ID';
COMMENT ON COLUMN adjustment_task.request_id IS '客户端幂等键，全局唯一';
COMMENT ON COLUMN adjustment_task.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN adjustment_task.classification IS '失衡分类：UNDERFLOW 欠流 / OVERFLOW 过流；未执行时为空';
COMMENT ON COLUMN adjustment_task.step_percent IS '本次步进幅度，单位 %，单次不超过 10';
COMMENT ON COLUMN adjustment_task.old_opening IS '调节前阀门开度，单位 %；未执行时为空';
COMMENT ON COLUMN adjustment_task.new_opening IS '调节后阀门开度，单位 %；未执行时为空';
COMMENT ON COLUMN adjustment_task.status IS '状态：EXECUTING 执行中 / ASSESSED 已评估 / ARCHIVED 已归档（终态）/ MANUAL_REVIEW 转人工复核';
COMMENT ON COLUMN adjustment_task.executed_at IS '调节执行时间（UTC），稳定期起点；未执行时为空';
COMMENT ON COLUMN adjustment_task.assessed_at IS '效果评估时间（UTC）；未评估时为空';
COMMENT ON COLUMN adjustment_task.archived_at IS '归档时间（UTC），归档为终态；未归档时为空';
COMMENT ON COLUMN adjustment_task.created_at IS '任务受理时间（UTC）';

CREATE TABLE IF NOT EXISTS valve_opening_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    valve_id BIGINT NOT NULL REFERENCES valve (id),
    task_id BIGINT NOT NULL REFERENCES adjustment_task (id),
    version_no INT NOT NULL,
    old_opening DECIMAL(5,2) NOT NULL,
    new_opening DECIMAL(5,2) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_valve_version UNIQUE (valve_id, version_no)
);
COMMENT ON TABLE valve_opening_version IS '阀门开度版本链，与开度写入同事务提交';
COMMENT ON COLUMN valve_opening_version.id IS '主键';
COMMENT ON COLUMN valve_opening_version.valve_id IS '阀门 ID';
COMMENT ON COLUMN valve_opening_version.task_id IS '产生本版本的调节任务 ID';
COMMENT ON COLUMN valve_opening_version.version_no IS '版本号，同一阀门从 1 递增';
COMMENT ON COLUMN valve_opening_version.old_opening IS '变更前开度，单位 %';
COMMENT ON COLUMN valve_opening_version.new_opening IS '变更后开度，单位 %';
COMMENT ON COLUMN valve_opening_version.created_at IS '版本生成时间（UTC）';

CREATE TABLE IF NOT EXISTS temperature_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_id BIGINT NOT NULL REFERENCES heating_unit (id),
    temperature DECIMAL(5,2) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE temperature_reading IS '单元回水温度采集记录';
COMMENT ON COLUMN temperature_reading.id IS '主键';
COMMENT ON COLUMN temperature_reading.unit_id IS '单元 ID';
COMMENT ON COLUMN temperature_reading.temperature IS '回水温度，单位 ℃';
COMMENT ON COLUMN temperature_reading.recorded_at IS '采集时间（UTC）';
COMMENT ON COLUMN temperature_reading.created_at IS '入库时间（UTC）';

CREATE TABLE IF NOT EXISTS assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES adjustment_task (id),
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    before_diff DECIMAL(5,2),
    after_diff DECIMAL(5,2),
    balanced BOOLEAN NOT NULL,
    detail VARCHAR(2048),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_assessment_request UNIQUE (request_id)
);
COMMENT ON TABLE assessment IS '调节效果评估记录，调节稳定满 4 小时后生成';
COMMENT ON COLUMN assessment.id IS '主键';
COMMENT ON COLUMN assessment.task_id IS '被评估的调节任务 ID';
COMMENT ON COLUMN assessment.request_id IS '客户端幂等键，全局唯一';
COMMENT ON COLUMN assessment.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN assessment.before_diff IS '调节前站内回水温差（最高-最低），单位 ℃';
COMMENT ON COLUMN assessment.after_diff IS '评估时站内回水温差（最高-最低），单位 ℃';
COMMENT ON COLUMN assessment.balanced IS '评估时是否平衡：温差不超过 2℃（恰 2℃ 合格）';
COMMENT ON COLUMN assessment.detail IS '前后温度分布对比快照（JSON），可由温度记录复算';
COMMENT ON COLUMN assessment.created_at IS '评估生成时间（UTC）';

CREATE TABLE IF NOT EXISTS valve_seal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    valve_id BIGINT NOT NULL REFERENCES valve (id),
    seal_code VARCHAR(64) NOT NULL,
    valve_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    sealed_at TIMESTAMP NOT NULL,
    resealed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_valve_seal_code UNIQUE (seal_code)
);
COMMENT ON TABLE valve_seal IS '平衡阀铅封台账：登记唯一铅封号、封存时开度版本与封存时刻，复封保留历史封号链';
COMMENT ON COLUMN valve_seal.id IS '主键';
COMMENT ON COLUMN valve_seal.valve_id IS '受控阀门 ID';
COMMENT ON COLUMN valve_seal.seal_code IS '铅封号，全局唯一（含历史封号）';
COMMENT ON COLUMN valve_seal.valve_version IS '封存时阀门开度版本：无变更为 0，首次成功调节后为 1，逐次 +1';
COMMENT ON COLUMN valve_seal.status IS '状态：ACTIVE 生效中 / RESEALED 已复封（历史封号，终态）';
COMMENT ON COLUMN valve_seal.sealed_at IS '封存时刻（UTC）';
COMMENT ON COLUMN valve_seal.resealed_at IS '复封终结时刻（UTC）；生效中为空';
COMMENT ON COLUMN valve_seal.created_at IS '台账登记时间（UTC）';

CREATE TABLE IF NOT EXISTS unseal_permit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seal_id BIGINT NOT NULL REFERENCES valve_seal (id),
    valve_id BIGINT NOT NULL REFERENCES valve (id),
    valve_version INT NOT NULL,
    approver1 VARCHAR(64) NOT NULL,
    approver2 VARCHAR(64) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    quota_total INT NOT NULL,
    quota_used INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    revoked_at TIMESTAMP,
    revoked_by VARCHAR(64),
    closed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE unseal_permit IS '临时解封单：引用铅封号与阀门版本，两名不同授权人确认，固定 UTC 半开窗口与最多两次调节额度';
COMMENT ON COLUMN unseal_permit.id IS '主键';
COMMENT ON COLUMN unseal_permit.seal_id IS '引用的铅封台账 ID';
COMMENT ON COLUMN unseal_permit.valve_id IS '受控阀门 ID（冗余自台账，便于按阀门串行化）';
COMMENT ON COLUMN unseal_permit.valve_version IS '最近核对的阀门开度版本：批准时为申请值，每次消费额度后同步为最新版本';
COMMENT ON COLUMN unseal_permit.approver1 IS '第一名授权人（两名按字典序排序存储）';
COMMENT ON COLUMN unseal_permit.approver2 IS '第二名授权人，须与第一名不同';
COMMENT ON COLUMN unseal_permit.window_start IS '解封有效窗口起点（UTC，含）';
COMMENT ON COLUMN unseal_permit.window_end IS '解封有效窗口终点（UTC，不含；恰到终点失效）';
COMMENT ON COLUMN unseal_permit.quota_total IS '调节额度上限，固定 2';
COMMENT ON COLUMN unseal_permit.quota_used IS '已消费调节额度，失败不消费';
COMMENT ON COLUMN unseal_permit.status IS '状态：ACTIVE 生效中 / REVOKED 已撤销 / CLOSED 已随复封终结';
COMMENT ON COLUMN unseal_permit.version IS '解封单自身版本，从 1 开始，每次成功变更 +1';
COMMENT ON COLUMN unseal_permit.revoked_at IS '撤销时刻（UTC）；未撤销为空';
COMMENT ON COLUMN unseal_permit.revoked_by IS '撤销操作授权人；未撤销为空';
COMMENT ON COLUMN unseal_permit.closed_at IS '随复封终结时刻（UTC）；未终结为空';
COMMENT ON COLUMN unseal_permit.created_at IS '批准时间（UTC）';

CREATE TABLE IF NOT EXISTS unseal_quota_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_id BIGINT NOT NULL REFERENCES unseal_permit (id),
    task_id BIGINT NOT NULL REFERENCES adjustment_task (id),
    valve_version INT NOT NULL,
    quota_used_after INT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE unseal_quota_event IS '解封额度消费轨迹：每次受控调节消费一次额度时登记，与开度写入同事务';
COMMENT ON COLUMN unseal_quota_event.id IS '主键';
COMMENT ON COLUMN unseal_quota_event.permit_id IS '解封单 ID';
COMMENT ON COLUMN unseal_quota_event.task_id IS '消费额度产生的调节任务 ID';
COMMENT ON COLUMN unseal_quota_event.valve_version IS '消费后阀门开度版本';
COMMENT ON COLUMN unseal_quota_event.quota_used_after IS '消费后已用额度';
COMMENT ON COLUMN unseal_quota_event.created_at IS '消费时刻（UTC）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    result_snapshot VARCHAR(4096) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE idempotency_record IS '新增写操作幂等台账：同键同参重放首次冻结结果，异参 409，失败不占键';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端幂等键，全局唯一（主键）';
COMMENT ON COLUMN idempotency_record.fingerprint IS '结构化幂等指纹：操作类型与全部业务参数（含版本、集合、精确时刻）长度前缀编码的 SHA-256';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型：SEAL_REGISTER / UNSEAL_APPROVE / UNSEAL_REVOKE / RESEAL / CONTROLLED_ADJUST';
COMMENT ON COLUMN idempotency_record.result_snapshot IS '首次成功结果的冻结快照（JSON），同键同参重放时原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '首次受理时间（UTC）';

CREATE TABLE IF NOT EXISTS late_temperature_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_id BIGINT NOT NULL REFERENCES heating_unit (id),
    task_id BIGINT,
    temperature DECIMAL(5,2) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_temperature_event IS '归档后迟到温度事件，仅记录不参与平衡计算';
COMMENT ON COLUMN late_temperature_event.id IS '主键';
COMMENT ON COLUMN late_temperature_event.unit_id IS '单元 ID';
COMMENT ON COLUMN late_temperature_event.task_id IS '关联的已归档任务 ID，可为空';
COMMENT ON COLUMN late_temperature_event.temperature IS '迟到的回水温度，单位 ℃';
COMMENT ON COLUMN late_temperature_event.recorded_at IS '采集时间（UTC），早于归档时间';
COMMENT ON COLUMN late_temperature_event.created_at IS '事件登记时间（UTC）';
