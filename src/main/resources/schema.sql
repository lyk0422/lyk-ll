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
