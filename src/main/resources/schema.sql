-- 灌区轮灌配水计划与欠供结转服务建表脚本（H2 内存库，MySQL 兼容模式）
-- 所有数据均为合成数据，仅在 JVM 生命周期内保留。

CREATE TABLE IF NOT EXISTS rotation_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '轮灌组编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '轮灌组名称',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    CONSTRAINT uk_rotation_group_code UNIQUE (code)
) COMMENT='轮灌组';

CREATE TABLE IF NOT EXISTS outlet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id BIGINT NOT NULL COMMENT '所属轮灌组 id',
    code VARCHAR(64) NOT NULL COMMENT '斗口编号，组内唯一',
    name VARCHAR(128) NOT NULL COMMENT '斗口名称',
    area_mu DECIMAL(14,2) NOT NULL COMMENT '灌溉面积（亩），权重计算依据',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    CONSTRAINT uk_outlet_group_code UNIQUE (group_id, code),
    CONSTRAINT fk_outlet_group FOREIGN KEY (group_id) REFERENCES rotation_group (id)
) COMMENT='斗口（合成数据）';

CREATE TABLE IF NOT EXISTS plan_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id BIGINT NOT NULL COMMENT '轮灌组 id',
    round_no INT NOT NULL COMMENT '轮次编号，每轮 7 天（UTC 日界）',
    version_no INT NOT NULL COMMENT '版本号，同轮同组内递增',
    total_water_m3 BIGINT NOT NULL COMMENT '组内可供水量（立方米）',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId+轮次+斗口计划明细（按斗口编号排序规范化）',
    status VARCHAR(16) NOT NULL COMMENT '版本状态：EFFECTIVE 生效 / SUPERSEDED 已废止',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间（UTC）',
    CONSTRAINT uk_plan_version_request UNIQUE (request_id),
    CONSTRAINT uk_plan_version_round_version UNIQUE (group_id, round_no, version_no),
    CONSTRAINT fk_plan_version_group FOREIGN KEY (group_id) REFERENCES rotation_group (id)
) COMMENT='配水计划版本，发布后冻结，修改须发布新版本';

CREATE TABLE IF NOT EXISTS plan_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_version_id BIGINT NOT NULL COMMENT '计划版本 id',
    outlet_id BIGINT NOT NULL COMMENT '斗口 id',
    outlet_code VARCHAR(64) NOT NULL COMMENT '斗口编号冗余，便于指纹规范化',
    base_m3 BIGINT NOT NULL COMMENT '按面积权重分配的基础水量（立方米）',
    carryover_in_m3 BIGINT NOT NULL DEFAULT 0 COMMENT '上一轮欠供结转补供水量（立方米）',
    planned_m3 BIGINT NOT NULL COMMENT '本轮计划水量=基础+结转（立方米）',
    CONSTRAINT fk_plan_detail_version FOREIGN KEY (plan_version_id) REFERENCES plan_version (id)
) COMMENT='配水计划斗口明细';

CREATE TABLE IF NOT EXISTS round_effective_plan (
    group_id BIGINT NOT NULL COMMENT '轮灌组 id',
    round_no INT NOT NULL COMMENT '轮次编号',
    plan_version_id BIGINT NOT NULL COMMENT '当前生效计划版本 id',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效指针更新时间（UTC）',
    PRIMARY KEY (group_id, round_no)
) COMMENT='同轮同组唯一生效版本指针，并发发布据此保证唯一成功';

CREATE TABLE IF NOT EXISTS metering (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id BIGINT NOT NULL COMMENT '轮灌组 id',
    outlet_id BIGINT NOT NULL COMMENT '斗口 id',
    round_no INT NOT NULL COMMENT '轮次编号',
    amount_m3 BIGINT NOT NULL COMMENT '该轮已确认实供水量快照（立方米），非累计增量',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后确认时间（UTC）',
    CONSTRAINT uk_metering_outlet_round UNIQUE (outlet_id, round_no)
) COMMENT='斗口实供计量，按斗口+轮次去重，重复上报不重复累计';

CREATE TABLE IF NOT EXISTS late_metering_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id BIGINT NOT NULL COMMENT '轮灌组 id',
    outlet_id BIGINT NOT NULL COMMENT '斗口 id',
    round_no INT NOT NULL COMMENT '轮次编号',
    amount_m3 BIGINT NOT NULL COMMENT '迟到上报的实供水量（立方米）',
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间（UTC）'
) COMMENT='封存后迟到计量事件，仅记录不改结转结果';

CREATE TABLE IF NOT EXISTS round_settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id BIGINT NOT NULL COMMENT '轮灌组 id',
    round_no INT NOT NULL COMMENT '轮次编号',
    sealed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '封存时间（UTC），封存为终态',
    CONSTRAINT uk_settlement_group_round UNIQUE (group_id, round_no)
) COMMENT='轮次欠供结转结算封存';

CREATE TABLE IF NOT EXISTS settlement_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    settlement_id BIGINT NOT NULL COMMENT '结算 id',
    outlet_id BIGINT NOT NULL COMMENT '斗口 id',
    planned_m3 BIGINT NOT NULL COMMENT '本轮计划水量（立方米）',
    actual_m3 BIGINT NOT NULL COMMENT '封存时已确认实供水量快照（立方米）',
    deficit_m3 BIGINT NOT NULL COMMENT '欠供水量=计划-实供，非负（立方米）',
    carryover_m3 BIGINT NOT NULL COMMENT '结转下一轮补供水量，上限本轮计划 30%（恰满允许）（立方米）',
    voided_m3 BIGINT NOT NULL COMMENT '超出结转上限作废水量（立方米）',
    CONSTRAINT fk_settlement_detail FOREIGN KEY (settlement_id) REFERENCES round_settlement (id)
) COMMENT='欠供结转明细，封存后不可变';

CREATE TABLE IF NOT EXISTS extra_purchase (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id BIGINT NOT NULL COMMENT '轮灌组 id',
    round_no INT NOT NULL COMMENT '轮次编号',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    amount_m3 BIGINT NOT NULL COMMENT '临时加购水量（立方米）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    CONSTRAINT uk_extra_purchase_request UNIQUE (request_id)
) COMMENT='临时加购水量，优先满足欠供斗口并按欠供比例分配';

CREATE TABLE IF NOT EXISTS extra_purchase_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    purchase_id BIGINT NOT NULL COMMENT '加购单 id',
    outlet_id BIGINT NOT NULL COMMENT '斗口 id',
    amount_m3 BIGINT NOT NULL COMMENT '分配到该斗口的加购水量（立方米）',
    CONSTRAINT fk_extra_purchase_detail FOREIGN KEY (purchase_id) REFERENCES extra_purchase (id)
) COMMENT='临时加购分配明细';
