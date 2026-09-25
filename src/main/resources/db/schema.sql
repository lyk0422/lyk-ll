-- 配网故障抢修排序与重要用户保电服务：建表脚本（H2 MODE=MySQL 兼容）

-- 工单业务序号（H2 序列，仅内存生命周期内递增）
CREATE SEQUENCE IF NOT EXISTS seq_order_no START WITH 1 INCREMENT BY 1;

-- 馈线
CREATE TABLE IF NOT EXISTS feeder (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    feeder_code  VARCHAR(64)  NOT NULL COMMENT '馈线编号',
    feeder_name  VARCHAR(128) NOT NULL COMMENT '馈线名称',
    substation   VARCHAR(128)          COMMENT '所属变电站',
    status       VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '馈线状态：NORMAL 正常，FAULT 故障',
    create_time  DATETIME(3)  NOT NULL,
    CONSTRAINT uk_feeder_code UNIQUE (feeder_code)
);

-- 重要用户分级（合成数据）
CREATE TABLE IF NOT EXISTS important_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_code    VARCHAR(64)  NOT NULL COMMENT '重要用户编号',
    user_name    VARCHAR(128) NOT NULL COMMENT '用户名称',
    grade        VARCHAR(16)  NOT NULL COMMENT '保电等级：SPECIAL 特级，FIRST 一级，SECOND 二级，NORMAL 普通',
    feeder_id    BIGINT                COMMENT '所属馈线 id，可空表示暂未挂接',
    contact      VARCHAR(64)           COMMENT '联系方式（合成）',
    create_time  DATETIME(3)  NOT NULL,
    CONSTRAINT uk_user_code UNIQUE (user_code)
);

-- 抢修班组
CREATE TABLE IF NOT EXISTS crew (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    crew_code    VARCHAR(64)  NOT NULL COMMENT '班组编号',
    crew_name    VARCHAR(128) NOT NULL COMMENT '班组名称',
    status       VARCHAR(16)  NOT NULL DEFAULT 'IDLE' COMMENT '班组状态：IDLE 空闲，BUSY 执行中（冗余快照，以 crew_execution 为准）',
    create_time  DATETIME(3)  NOT NULL,
    CONSTRAINT uk_crew_code UNIQUE (crew_code)
);

-- 故障工单（接报/研判/派单/抢修/复电/日结）
CREATE TABLE IF NOT EXISTS fault_order (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no            VARCHAR(40)  NOT NULL COMMENT '工单号，业务主键',
    feeder_id           BIGINT                COMMENT '故障馈线 id，可空表示馈线不明',
    fault_point         VARCHAR(256) NOT NULL COMMENT '故障点（接报去重键）',
    description         VARCHAR(512)          COMMENT '故障描述',
    affected_users      INT          NOT NULL COMMENT '影响用户数，单位：户',
    report_time         DATETIME(3)  NOT NULL COMMENT '接报时间（Asia/Shanghai）',
    outage_time         DATETIME(3)  NOT NULL COMMENT '停电起始时间，发电车 5 分钟计时基准',
    status              VARCHAR(24)  NOT NULL COMMENT '状态：REPORTED 已接报，ASSESSED 已研判，WAITING 挂单等待，DISPATCHED 已派单，REPAIRING 抢修中，RESTORED 已复电，SEALED 日结封存（终态），MERGED 已合并（终态）',
    user_grade          VARCHAR(16)           COMMENT '研判命中的最高保电等级快照：SPECIAL/FIRST/SECOND/NORMAL，空=未研判',
    important_user_id   BIGINT                COMMENT '研判命中的最高等级重要用户 id，可空',
    crew_id             BIGINT                COMMENT '执行班组 id，空=未派单',
    wait_crew_id        BIGINT                COMMENT '挂单等待的目标班组 id，空=未挂单',
    assigned_time       DATETIME(3)           COMMENT '派单时间，空=未派单',
    repair_start_time   DATETIME(3)           COMMENT '抢修开始时间，空=未开始',
    restored_time       DATETIME(3)           COMMENT '复电时间（双确认齐备时刻），空=未复电',
    sealed_time         DATETIME(3)           COMMENT '日结封存时间，空=未封存',
    merged_into_id      BIGINT                COMMENT '合并后指向的新工单 id，空=未合并',
    merged_into_no      VARCHAR(40)           COMMENT '合并后指向的新工单号，空=未合并',
    create_time         DATETIME(3)  NOT NULL,
    update_time         DATETIME(3)  NOT NULL,
    CONSTRAINT uk_order_no UNIQUE (order_no)
);
CREATE INDEX IF NOT EXISTS idx_order_feeder_time ON fault_order (feeder_id, report_time);
CREATE INDEX IF NOT EXISTS idx_order_status ON fault_order (status);
CREATE INDEX IF NOT EXISTS idx_order_point_time ON fault_order (fault_point, report_time);

-- 班组执行占用：行存在即表示该班组有一张执行中工单，主键保证同一班组同时刻唯一执行单
CREATE TABLE IF NOT EXISTS crew_execution (
    crew_id       BIGINT NOT NULL COMMENT '班组 id（主键，一个班组至多一行占用）',
    order_id      BIGINT NOT NULL COMMENT '执行中工单 id',
    assigned_time DATETIME(3) NOT NULL COMMENT '占用开始（派单）时间',
    PRIMARY KEY (crew_id)
);

-- 复电双确认链：调度与现场各确认一次，单方确认不生效
CREATE TABLE IF NOT EXISTS restore_confirm (
    order_id                BIGINT NOT NULL COMMENT '工单 id',
    dispatcher_confirm_time DATETIME(3) COMMENT '调度确认时间，空=调度未确认',
    dispatcher_actor        VARCHAR(64) COMMENT '调度确认人',
    field_confirm_time      DATETIME(3) COMMENT '现场确认时间，空=现场未确认',
    field_actor             VARCHAR(64) COMMENT '现场确认人',
    completed_time          DATETIME(3) COMMENT '双确认齐备时间，空=未完成复电',
    PRIMARY KEY (order_id)
);

-- 负荷恢复记录：与复电确认在同一事务写入
CREATE TABLE IF NOT EXISTS load_recovery (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT NOT NULL COMMENT '工单 id',
    restored_load_kw DECIMAL(12,2) NOT NULL COMMENT '恢复负荷，单位：千瓦',
    recovered_users  INT NOT NULL COMMENT '恢复用户数，单位：户',
    record_time      DATETIME(3) NOT NULL COMMENT '记录时间（等于复电时间）',
    create_time      DATETIME(3) NOT NULL,
    CONSTRAINT uk_load_order UNIQUE (order_id)
);

-- 发电车流程记录（特级用户停电满 5 分钟触发）
CREATE TABLE IF NOT EXISTS generator_truck (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT NOT NULL COMMENT '工单 id',
    grade        VARCHAR(16) NOT NULL COMMENT '触发时保电等级快照',
    outage_time  DATETIME(3) NOT NULL COMMENT '停电起始时间',
    trigger_time DATETIME(3) NOT NULL COMMENT '发电车流程启动时间（恰满 5 分钟判定）',
    status       VARCHAR(16) NOT NULL COMMENT '状态：TRIGGERED 已启动，WITHDRAWN 复电撤回',
    note         VARCHAR(256) COMMENT '备注',
    create_time  DATETIME(3) NOT NULL,
    CONSTRAINT uk_generator_order UNIQUE (order_id)
);

-- 工单轨迹（合并、挂单、迟到回报等全部留痕）
CREATE TABLE IF NOT EXISTS order_event (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT NOT NULL COMMENT '工单 id',
    event_type       VARCHAR(32) NOT NULL COMMENT '事件类型：REPORT/ASSESS/DISPATCH/PARK/REPAIR_START/RESTORE_CONFIRM/RESTORED/GENERATOR_START/GENERATOR_STOP/MERGE_IN/MERGE_OUT/SEAL/LATE_AFTER_SEAL/DEDUP_MERGE',
    event_time       DATETIME(3) NOT NULL COMMENT '事件发生时间',
    actor            VARCHAR(32) NOT NULL COMMENT '事件主体：DISPATCHER 调度，FIELD 现场，SYSTEM 系统，CALLER 报修人',
    detail           VARCHAR(1024) COMMENT '事件详情',
    related_order_id BIGINT COMMENT '关联工单 id（如合并目标/来源）',
    create_time      DATETIME(3) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_order_time ON order_event (order_id, event_time, id);

-- 幂等请求指纹：requestId + 业务类型唯一，指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id   VARCHAR(64) NOT NULL COMMENT '客户端幂等 requestId',
    api_type     VARCHAR(32) NOT NULL COMMENT '业务类型：REPORT/ASSESS/DISPATCH/REPAIR_START/RESTORE_CONFIRM/MERGE/SEAL',
    fingerprint  CHAR(64) NOT NULL COMMENT 'SHA-256 指纹（requestId 与全部业务字段）',
    ref_key      VARCHAR(64) COMMENT '首次结果业务引用（工单号等）',
    response_doc CLOB COMMENT '首次响应快照 JSON',
    status_code  INT NOT NULL DEFAULT 200 COMMENT '首次响应 HTTP 状态码',
    create_time  DATETIME(3) NOT NULL,
    CONSTRAINT uk_idem_req_type UNIQUE (request_id, api_type)
);
