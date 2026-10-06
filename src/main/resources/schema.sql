-- 行道树风险修剪审批流程 schema（H2 MODE=MySQL，时间均为 Asia/Shanghai 本地时间）
CREATE TABLE IF NOT EXISTS road_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '路段编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '路段名称',
    created_at TIMESTAMP NOT NULL COMMENT '建档时间',
    CONSTRAINT uq_segment_code UNIQUE (code)
) COMMENT='路段档案';

CREATE TABLE IF NOT EXISTS tree (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id BIGINT NOT NULL COMMENT '所属路段ID',
    tree_code VARCHAR(64) NOT NULL COMMENT '树木编号，全局唯一',
    tilt_degrees DOUBLE NOT NULL COMMENT '树干倾斜角，单位度',
    disease_level INT NOT NULL COMMENT '病害等级0-3，越大越重',
    age_years INT NOT NULL COMMENT '树龄，单位年',
    wind_exposure INT NOT NULL COMMENT '风口暴露等级0-3，越大越暴露',
    risk_score DOUBLE NOT NULL COMMENT '合成评估模型得分',
    risk_level INT NOT NULL COMMENT '风险等级1-4，1为最高风险',
    evaluated_at TIMESTAMP NOT NULL COMMENT '最近一次评估时间',
    CONSTRAINT uq_tree_code UNIQUE (tree_code),
    CONSTRAINT fk_tree_segment FOREIGN KEY (segment_id) REFERENCES road_segment(id)
) COMMENT='行道树档案';

CREATE TABLE IF NOT EXISTS pruning_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tree_id BIGINT NOT NULL COMMENT '树木ID',
    segment_id BIGINT NOT NULL COMMENT '路段ID（冗余便于台账统计）',
    risk_level INT NOT NULL COMMENT '生成计划时的风险等级',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING待修剪/CLOSED已闭环/ESCALATED逾期督办/RESCHEDULED已重排',
    created_at TIMESTAMP NOT NULL COMMENT '计划生成时间',
    deadline TIMESTAMP NOT NULL COMMENT '修剪期限=生成时间+7日，恰第7日闭环有效',
    closed_at TIMESTAMP NULL COMMENT '闭环时间，NULL表示未闭环',
    closed_on_time TINYINT NULL COMMENT '1按期闭环/0逾期闭环，NULL表示未闭环',
    CONSTRAINT fk_plan_tree FOREIGN KEY (tree_id) REFERENCES tree(id)
) COMMENT='修剪计划';

CREATE TABLE IF NOT EXISTS work_permit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_no VARCHAR(64) NOT NULL COMMENT '许可编号，全局唯一',
    segment_id BIGINT NOT NULL COMMENT '作业路段ID',
    plan_id BIGINT NOT NULL COMMENT '关联修剪计划ID',
    work_type VARCHAR(16) NOT NULL COMMENT 'HIGH_ALTITUDE高空作业/GROUND地面作业',
    status VARCHAR(16) NOT NULL COMMENT 'EXECUTING执行中/COMPLETED已完成/CANCELLED已取消',
    executing_flag INT NULL COMMENT '执行中占位=1，非执行中为NULL，配合唯一约束保证同路段同时刻仅一张执行中许可',
    deadline TIMESTAMP NOT NULL COMMENT '既定时限，取计划生成时期限，重排不修改',
    created_at TIMESTAMP NOT NULL COMMENT '许可签发时间',
    completed_at TIMESTAMP NULL COMMENT '完成时间，NULL表示未完成',
    CONSTRAINT uq_permit_no UNIQUE (permit_no),
    CONSTRAINT uq_permit_executing UNIQUE (segment_id, executing_flag),
    CONSTRAINT fk_permit_plan FOREIGN KEY (plan_id) REFERENCES pruning_plan(id)
) COMMENT='作业许可';

CREATE TABLE IF NOT EXISTS work_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_id BIGINT NOT NULL COMMENT '作业许可ID，一张许可仅一次回传',
    before_photo VARCHAR(128) NOT NULL COMMENT '作业前照片标识',
    after_photo VARCHAR(128) NOT NULL COMMENT '作业后照片标识',
    disposal_record VARCHAR(256) NOT NULL COMMENT '弃枝清运记录',
    reported_at TIMESTAMP NOT NULL COMMENT '回传时间',
    CONSTRAINT uq_report_permit UNIQUE (permit_id),
    CONSTRAINT fk_report_permit FOREIGN KEY (permit_id) REFERENCES work_permit(id)
) COMMENT='作业回传';

CREATE TABLE IF NOT EXISTS gale_warning (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warning_no VARCHAR(64) NOT NULL COMMENT '预警编号，全局唯一',
    level INT NOT NULL COMMENT '合成预警级别1-4，越大越强',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE生效中/CANCELLED已解除',
    published_at TIMESTAMP NOT NULL COMMENT '发布时间',
    cancelled_at TIMESTAMP NULL COMMENT '解除时间，NULL表示未解除',
    CONSTRAINT uq_warning_no UNIQUE (warning_no)
) COMMENT='大风预警';

CREATE TABLE IF NOT EXISTS segment_freeze (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id BIGINT NOT NULL COMMENT '被冻结路段ID，同一时刻唯一',
    warning_id BIGINT NOT NULL COMMENT '触发冻结的预警ID',
    frozen_at TIMESTAMP NOT NULL COMMENT '冻结时间',
    CONSTRAINT uq_freeze_segment UNIQUE (segment_id),
    CONSTRAINT fk_freeze_warning FOREIGN KEY (warning_id) REFERENCES gale_warning(id)
) COMMENT='高风险路段高空作业冻结';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL COMMENT '幂等键，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'requestId与全部业务字段的SHA-256指纹',
    endpoint VARCHAR(128) NOT NULL COMMENT '业务端点标识',
    response_status INT NULL COMMENT '首次响应状态码，NULL表示处理中',
    response_body CLOB NULL COMMENT '首次响应报文JSON，NULL表示处理中',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间',
    CONSTRAINT uq_idem_request UNIQUE (request_id)
) COMMENT='写操作幂等记录';
