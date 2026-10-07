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
    seal_no VARCHAR(64) NOT NULL,
    valve_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    sealed_at TIMESTAMP NOT NULL,
    resealed_at TIMESTAMP,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    CONSTRAINT uk_valve_seal_no UNIQUE (seal_no),
    CONSTRAINT uk_valve_seal_request UNIQUE (request_id)
);
COMMENT ON TABLE valve_seal IS '阀门铅封台账：登记/复封历史链，同一阀门至多一条 ACTIVE';
COMMENT ON COLUMN valve_seal.id IS '主键';
COMMENT ON COLUMN valve_seal.valve_id IS '受控阀门 ID';
COMMENT ON COLUMN valve_seal.seal_no IS '铅封号，全局唯一';
COMMENT ON COLUMN valve_seal.valve_version IS '登记/复封时核对的开度版本（valve_opening_version 当前最大版本号，无版本为 0）';
COMMENT ON COLUMN valve_seal.status IS '状态：ACTIVE 完整有效 / RESEALED 已被复封替换（历史保留）';
COMMENT ON COLUMN valve_seal.version IS '乐观锁版本，从 1 开始，成功变更加 1';
COMMENT ON COLUMN valve_seal.sealed_at IS '封存时刻（UTC）';
COMMENT ON COLUMN valve_seal.resealed_at IS '被复封替换时刻（UTC）；未替换时为空';
COMMENT ON COLUMN valve_seal.request_id IS '客户端幂等键（登记或复封请求的键），全局唯一';
COMMENT ON COLUMN valve_seal.fingerprint IS '幂等指纹：requestId 与全部业务字段的结构化 SHA-256';

CREATE TABLE IF NOT EXISTS unseal_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seal_id BIGINT NOT NULL REFERENCES valve_seal (id),
    valve_id BIGINT NOT NULL REFERENCES valve (id),
    seal_no VARCHAR(64) NOT NULL,
    valve_version INT NOT NULL,
    approver_a VARCHAR(64) NOT NULL,
    approver_b VARCHAR(64) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    quota_total INT NOT NULL,
    quota_used INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    revoke_request_id VARCHAR(64),
    revoke_fingerprint VARCHAR(128),
    CONSTRAINT uk_unseal_request UNIQUE (request_id),
    CONSTRAINT uk_unseal_revoke_request UNIQUE (revoke_request_id)
);
COMMENT ON TABLE unseal_order IS '临时解封单：批准窗口与调节额度内的受控调节授权';
COMMENT ON COLUMN unseal_order.id IS '主键';
COMMENT ON COLUMN unseal_order.seal_id IS '引用的铅封台账 ID';
COMMENT ON COLUMN unseal_order.valve_id IS '受控阀门 ID（冗余自铅封，便于锁定）';
COMMENT ON COLUMN unseal_order.seal_no IS '引用的铅封号';
COMMENT ON COLUMN unseal_order.valve_version IS '批准时引用的阀门开度版本；受控调节要求当前版本 = 本版本 + 已消费额度';
COMMENT ON COLUMN unseal_order.approver_a IS '授权人甲，须与授权人乙不同';
COMMENT ON COLUMN unseal_order.approver_b IS '授权人乙，须与授权人甲不同';
COMMENT ON COLUMN unseal_order.window_start IS '有效窗口起点（UTC，半开区间，含）';
COMMENT ON COLUMN unseal_order.window_end IS '有效窗口终点（UTC，半开区间，不含；恰到终点失效）';
COMMENT ON COLUMN unseal_order.quota_total IS '调节额度上限，固定为 2';
COMMENT ON COLUMN unseal_order.quota_used IS '已消费额度，每次成功受控调节加 1';
COMMENT ON COLUMN unseal_order.status IS '状态：ACTIVE 有效 / REVOKED 已撤销 / CLOSED 复封终结';
COMMENT ON COLUMN unseal_order.version IS '乐观锁版本，从 1 开始，成功变更（含消费额度）加 1';
COMMENT ON COLUMN unseal_order.created_at IS '解封批准时刻（UTC），生效不改变开度';
COMMENT ON COLUMN unseal_order.revoked_at IS '撤销时刻（UTC）；未撤销时为空';
COMMENT ON COLUMN unseal_order.request_id IS '客户端幂等键（批准请求），全局唯一';
COMMENT ON COLUMN unseal_order.fingerprint IS '幂等指纹：requestId 与全部业务字段（含窗口精确时刻）的结构化 SHA-256';
COMMENT ON COLUMN unseal_order.revoke_request_id IS '撤销请求的幂等键，全局唯一；未撤销时为空';
COMMENT ON COLUMN unseal_order.revoke_fingerprint IS '撤销请求的幂等指纹；未撤销时为空';

CREATE TABLE IF NOT EXISTS unseal_quota_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unseal_id BIGINT NOT NULL REFERENCES unseal_order (id),
    task_id BIGINT NOT NULL REFERENCES adjustment_task (id),
    request_id VARCHAR(64) NOT NULL,
    used_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_unseal_quota_request UNIQUE (request_id)
);
COMMENT ON TABLE unseal_quota_usage IS '解封额度消费轨迹：每次受控调节消费一次额度，同幂等键不重复消费';
COMMENT ON COLUMN unseal_quota_usage.id IS '主键';
COMMENT ON COLUMN unseal_quota_usage.unseal_id IS '解封单 ID';
COMMENT ON COLUMN unseal_quota_usage.task_id IS '消费额度产生的调节任务 ID';
COMMENT ON COLUMN unseal_quota_usage.request_id IS '受控调节的客户端幂等键，全局唯一';
COMMENT ON COLUMN unseal_quota_usage.used_at IS '额度消费时刻（UTC）';

CREATE TABLE IF NOT EXISTS coupled_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL REFERENCES heat_station (id),
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    dissolved_at TIMESTAMP,
    CONSTRAINT uk_coupled_group_request UNIQUE (request_id)
);
COMMENT ON TABLE coupled_group IS '联调组：同站 2~5 只不同阀的耦合调节分组，阀只能属于一个活动组';
COMMENT ON COLUMN coupled_group.id IS '主键';
COMMENT ON COLUMN coupled_group.station_id IS '所属换热站 ID';
COMMENT ON COLUMN coupled_group.status IS '状态：ACTIVE 活动 / DISSOLVED 已解除（仅释放组归属，不回拨开度、不返还额度）';
COMMENT ON COLUMN coupled_group.version IS '联调单乐观锁版本，从 1 开始，成功修改/解除加 1，旧版 409';
COMMENT ON COLUMN coupled_group.request_id IS '建组客户端幂等键，全局唯一';
COMMENT ON COLUMN coupled_group.fingerprint IS '幂等指纹：requestId 与全阀集合及权重的结构化 SHA-256';
COMMENT ON COLUMN coupled_group.created_at IS '建组时刻（UTC）';
COMMENT ON COLUMN coupled_group.dissolved_at IS '解除时刻（UTC）；未解除时为空';

CREATE TABLE IF NOT EXISTS coupled_group_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES coupled_group (id),
    valve_id BIGINT NOT NULL REFERENCES valve (id),
    weight INT NOT NULL,
    valve_version INT NOT NULL,
    CONSTRAINT uk_coupled_member UNIQUE (group_id, valve_id),
    CONSTRAINT ck_coupled_member_weight CHECK (weight >= 1 AND weight <= 10)
);
COMMENT ON TABLE coupled_group_member IS '联调组成员：建组/改组时冻结的阀门权重与开度版本';
COMMENT ON COLUMN coupled_group_member.id IS '主键';
COMMENT ON COLUMN coupled_group_member.group_id IS '所属联调组 ID';
COMMENT ON COLUMN coupled_group_member.valve_id IS '成员阀门 ID，组内唯一；活动组间不重复（服务层在站行锁内校验）';
COMMENT ON COLUMN coupled_group_member.weight IS '联调权重，正整数 1~10，建组时冻结';
COMMENT ON COLUMN coupled_group_member.valve_version IS '建组/改组时冻结的阀门开度版本（valve_opening_version 当前最大版本号，无版本为 0）';

CREATE TABLE IF NOT EXISTS coupled_adjustment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES coupled_group (id),
    station_id BIGINT NOT NULL REFERENCES heat_station (id),
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    adjust_at TIMESTAMP NOT NULL,
    executed_at TIMESTAMP NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_coupled_adjustment_request UNIQUE (request_id)
);
COMMENT ON TABLE coupled_adjustment IS '联调单：全组目标开度的原子提交记录，失败全组零变化';
COMMENT ON COLUMN coupled_adjustment.id IS '主键';
COMMENT ON COLUMN coupled_adjustment.group_id IS '所属联调组 ID';
COMMENT ON COLUMN coupled_adjustment.station_id IS '所属换热站 ID（冗余自联调组，便于锁定）';
COMMENT ON COLUMN coupled_adjustment.request_id IS '客户端幂等键，全局唯一；失败不占键';
COMMENT ON COLUMN coupled_adjustment.fingerprint IS '幂等指纹：requestId、全阀集合、权重、逐阀目标/版本/解封单与 UTC 时刻的结构化 SHA-256';
COMMENT ON COLUMN coupled_adjustment.adjust_at IS '客户端声明的联调时刻（UTC），参与幂等指纹';
COMMENT ON COLUMN coupled_adjustment.executed_at IS '联调实际执行时刻（UTC），逐阀任务稳定期起点';
COMMENT ON COLUMN coupled_adjustment.version IS '联调单版本，从 1 开始';
COMMENT ON COLUMN coupled_adjustment.created_at IS '联调单受理时刻（UTC）';

CREATE TABLE IF NOT EXISTS coupled_adjustment_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupled_id BIGINT NOT NULL REFERENCES coupled_adjustment (id),
    valve_id BIGINT NOT NULL REFERENCES valve (id),
    weight INT NOT NULL,
    old_opening DECIMAL(5,2) NOT NULL,
    new_opening DECIMAL(5,2) NOT NULL,
    unseal_id BIGINT,
    task_id BIGINT,
    CONSTRAINT uk_coupled_item UNIQUE (coupled_id, valve_id)
);
COMMENT ON TABLE coupled_adjustment_item IS '联调逐阀明细：联调前后开度与权重留痕，可复算净加权开度及逐阀额度';
COMMENT ON COLUMN coupled_adjustment_item.id IS '主键';
COMMENT ON COLUMN coupled_adjustment_item.coupled_id IS '所属联调单 ID';
COMMENT ON COLUMN coupled_adjustment_item.valve_id IS '成员阀门 ID';
COMMENT ON COLUMN coupled_adjustment_item.weight IS '联调时冻结的阀门权重，正整数 1~10';
COMMENT ON COLUMN coupled_adjustment_item.old_opening IS '联调前开度，单位 %，0~100 整数百分点';
COMMENT ON COLUMN coupled_adjustment_item.new_opening IS '联调目标开度，单位 %，0~100 整数百分点；与旧值相等表示本阀不变';
COMMENT ON COLUMN coupled_adjustment_item.unseal_id IS '铅封阀使用的解封单 ID；未封阀为空';
COMMENT ON COLUMN coupled_adjustment_item.task_id IS '实际变化阀生成的调节任务 ID（沿用原温差评估及归档）；不变阀为空';

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
