-- 灌区轮灌配水计划与欠供结转服务建表脚本（H2 MODE=MySQL，数据仅存于 JVM 内存生命周期）。
-- 时间列一律存 UTC 时刻；水量列单位均为立方米；面积列单位为平方米。

-- 轮灌组：灌区按轮灌组编组，每组含若干斗口
CREATE TABLE IF NOT EXISTS irr_group (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_code   VARCHAR(64)  NOT NULL COMMENT '轮灌组编号，业务唯一键',
    group_name   VARCHAR(128) NOT NULL COMMENT '轮灌组名称',
    created_at   TIMESTAMP    NOT NULL COMMENT '建档时间，UTC',
    CONSTRAINT uk_irr_group_code UNIQUE (group_code)
);

-- 斗口合成基础数据
CREATE TABLE IF NOT EXISTS irr_outlet (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code  VARCHAR(64)  NOT NULL COMMENT '斗口编号，业务唯一键',
    outlet_name  VARCHAR(128) NOT NULL COMMENT '斗口名称',
    created_at   TIMESTAMP    NOT NULL COMMENT '建档时间，UTC',
    CONSTRAINT uk_irr_outlet_code UNIQUE (outlet_code)
);

-- 轮灌组与斗口成员关系（含灌溉面积），一个斗口同一时间只编入一个组
CREATE TABLE IF NOT EXISTS irr_group_outlet (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_code           VARCHAR(64) NOT NULL COMMENT '轮灌组编号',
    outlet_code          VARCHAR(64) NOT NULL COMMENT '斗口编号',
    area_square_meter    BIGINT      NOT NULL COMMENT '灌溉面积，平方米，必须为正',
    created_at           TIMESTAMP   NOT NULL COMMENT '入组时间，UTC',
    CONSTRAINT uk_irr_group_outlet UNIQUE (group_code, outlet_code)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_irr_outlet_membership ON irr_group_outlet (outlet_code);

-- 配水计划版本：发布后冻结；修改须发新版本；同轮同组只允许一个 EFFECTIVE 版本
CREATE TABLE IF NOT EXISTS irr_plan_version (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_code             VARCHAR(64)  NOT NULL COMMENT '轮灌组编号',
    turn_no                BIGINT       NOT NULL COMMENT '轮次编号，自 1970-01-01 UTC 起每 7 天一轮',
    version_no             INT          NOT NULL COMMENT '同轮同组版本号，从 1 递增；旧版本可查',
    status                 VARCHAR(16)  NOT NULL COMMENT '版本状态：EFFECTIVE=生效，SUPERSEDED=已被新版本取代',
    available_cubic_meter  BIGINT       NOT NULL COMMENT '组内本轮可供水量，立方米',
    request_id             VARCHAR(96)  NOT NULL COMMENT '首次发布使用的幂等请求键',
    fingerprint            CHAR(64)     NOT NULL COMMENT '发布指纹 SHA-256，覆盖 requestId/轮次/全部斗口明细',
    effective_unique_key   VARCHAR(128) COMMENT '生效唯一键 turn_no|group_code，非生效版本为 NULL；数据库层保证同轮同组唯一生效版本',
    published_at           TIMESTAMP    NOT NULL COMMENT '发布时间，UTC',
    created_at             TIMESTAMP    NOT NULL COMMENT '记录创建时间，UTC',
    CONSTRAINT uk_irr_plan_version_no UNIQUE (turn_no, group_code, version_no),
    CONSTRAINT uk_irr_plan_effective UNIQUE (effective_unique_key),
    CONSTRAINT uk_irr_plan_request UNIQUE (request_id)
);

-- 计划版本明细：斗口维度的权重与计划量（按斗口编号排序规范化）
CREATE TABLE IF NOT EXISTS irr_plan_line (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id                BIGINT       NOT NULL COMMENT '所属计划版本 ID',
    outlet_code            VARCHAR(64)  NOT NULL COMMENT '斗口编号',
    area_square_meter      BIGINT       NOT NULL COMMENT '灌溉面积，平方米',
    weight                 DECIMAL(10,4) NOT NULL COMMENT '组内面积权重，四位小数',
    plan_cubic_meter       BIGINT       NOT NULL COMMENT '本轮按权重分配的计划配水量，立方米；同版本合计等于可供总量',
    carryover_cubic_meter  BIGINT       NOT NULL DEFAULT 0 COMMENT '上一轮封存后结转至本轮的欠供补供量，立方米；与按权重分配量构成本轮总供水目标',
    CONSTRAINT uk_irr_plan_line UNIQUE (plan_id, outlet_code)
);

-- 实供计量快照：斗口＋轮次唯一，重复上报不重复累计
CREATE TABLE IF NOT EXISTS irr_metering (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    turn_no                   BIGINT      NOT NULL COMMENT '轮次编号',
    group_code                VARCHAR(64) NOT NULL COMMENT '轮灌组编号，冗余便于结转账务',
    outlet_code               VARCHAR(64) NOT NULL COMMENT '斗口编号',
    delivered_cubic_meter     BIGINT      NOT NULL COMMENT '该斗口本轮最后一次已确认累计实供读数，立方米',
    event_count               INT         NOT NULL COMMENT '已接受的不同计量事件数；重复事件不递增',
    confirmed_at              TIMESTAMP   NOT NULL COMMENT '最后一次确认计量时间，UTC',
    created_at                TIMESTAMP   NOT NULL COMMENT '首次计量时间，UTC',
    CONSTRAINT uk_irr_metering_turn_outlet UNIQUE (turn_no, outlet_code)
);

-- 计量上报事件去重表：同一 eventId 重放只返回首次结果
CREATE TABLE IF NOT EXISTS irr_meter_event (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id                  VARCHAR(96) NOT NULL COMMENT '计量上报事件幂等键',
    turn_no                   BIGINT      NOT NULL COMMENT '轮次编号',
    group_code                VARCHAR(64) NOT NULL COMMENT '轮灌组编号',
    outlet_code               VARCHAR(64) NOT NULL COMMENT '斗口编号',
    delivered_cubic_meter     BIGINT      NOT NULL COMMENT '该事件上报的本轮累计实供读数，立方米',
    created_at                TIMESTAMP   NOT NULL COMMENT '首次接收时间，UTC',
    CONSTRAINT uk_irr_meter_event UNIQUE (event_id)
);

-- 临时加购水量单：优先满足欠供斗口，按欠供比例分配
CREATE TABLE IF NOT EXISTS irr_extra_purchase (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id                VARCHAR(96) NOT NULL COMMENT '加购请求幂等键',
    group_code                VARCHAR(64) NOT NULL COMMENT '轮灌组编号',
    turn_no                   BIGINT      NOT NULL COMMENT '轮次编号',
    amount_cubic_meter        BIGINT      NOT NULL COMMENT '加购水量，立方米，必须为正',
    created_at                TIMESTAMP   NOT NULL COMMENT '创建时间，UTC',
    CONSTRAINT uk_irr_extra_request UNIQUE (request_id)
);

-- 加购水量分配明细：按分配时点欠供比例落账
CREATE TABLE IF NOT EXISTS irr_extra_allocation (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_id               BIGINT      NOT NULL COMMENT '所属加购单 ID',
    outlet_code               VARCHAR(64) NOT NULL COMMENT '获配斗口编号（仅欠供斗口）',
    shortage_cubic_meter      BIGINT      NOT NULL COMMENT '分配时点欠供量，立方米',
    allocated_cubic_meter     BIGINT      NOT NULL COMMENT '本次获配水量，立方米；各斗口合计等于加购量与欠供总额的较小值',
    CONSTRAINT uk_irr_extra_alloc UNIQUE (purchase_id, outlet_code)
);

-- 轮次封存主记录：封存为终态，重复封存幂等成功
CREATE TABLE IF NOT EXISTS irr_settlement (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    turn_no                   BIGINT      NOT NULL COMMENT '被结算轮次编号',
    group_code                VARCHAR(64) NOT NULL COMMENT '轮灌组编号',
    status                    VARCHAR(16) NOT NULL COMMENT '封存状态：SEALED=已封存终态，不可更改',
    sealed_at                 TIMESTAMP   NOT NULL COMMENT '首次封存时间，UTC',
    CONSTRAINT uk_irr_settlement UNIQUE (turn_no, group_code)
);

-- 欠供结转明细：封存时以最后一次已确认计量快照计算，结果稳定可复算
CREATE TABLE IF NOT EXISTS irr_carryover (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    turn_no                   BIGINT      NOT NULL COMMENT '被结算轮次编号（欠供发生轮）',
    group_code                VARCHAR(64) NOT NULL COMMENT '轮灌组编号',
    outlet_code               VARCHAR(64) NOT NULL COMMENT '斗口编号',
    plan_cubic_meter          BIGINT      NOT NULL COMMENT '本轮按权重分配的计划量快照，立方米（不含上轮结转补供量，为 30% 上限基数）',
    delivered_cubic_meter     BIGINT      NOT NULL COMMENT '封存时点已确认实供量快照，立方米',
    shortage_cubic_meter      BIGINT      NOT NULL COMMENT '欠供量=max(本轮按权重计划量+上轮结转补供量-实供,0)，立方米',
    carryover_cubic_meter     BIGINT      NOT NULL COMMENT '可结转下轮补供量，立方米，上限为本轮按权重计划量 30%（恰满允许）',
    voided_cubic_meter        BIGINT      NOT NULL COMMENT '超出 30% 上限而作废的欠供量，立方米',
    sealed_at                 TIMESTAMP   NOT NULL COMMENT '封存时间，UTC',
    CONSTRAINT uk_irr_carryover UNIQUE (turn_no, group_code, outlet_code)
);

-- 迟到计量事件：封存后到达的计量只登记事件，绝不改结转结果
CREATE TABLE IF NOT EXISTS irr_late_meter_event (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    turn_no                   BIGINT      NOT NULL COMMENT '计量所属轮次编号',
    group_code                VARCHAR(64) NOT NULL COMMENT '轮灌组编号',
    outlet_code               VARCHAR(64) NOT NULL COMMENT '斗口编号',
    event_id                  VARCHAR(96) NOT NULL COMMENT '迟到计量事件幂等键',
    delivered_cubic_meter     BIGINT      NOT NULL COMMENT '迟到事件上报的累计实供读数，立方米（仅登记不落账）',
    reason                    VARCHAR(32) NOT NULL COMMENT '迟到原因：AFTER_SEALED=该轮该组已封存',
    received_at               TIMESTAMP   NOT NULL COMMENT '接收时间，UTC',
    CONSTRAINT uk_irr_late_meter_event UNIQUE (event_id)
);
