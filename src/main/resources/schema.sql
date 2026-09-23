-- 铁路检修封锁窗：H2 内存库（MODE=MySQL）建表脚本，随应用启动自动执行。
-- 所有时间均为 UTC；窗口语义为半开区间 [start,end)。

-- 封锁申请主表
CREATE TABLE IF NOT EXISTS possession_request (
    id                  VARCHAR(64)  NOT NULL COMMENT '封锁申请ID（业务请求键）',
    start_time          DATETIME(6)  NOT NULL COMMENT '封锁开始时间(UTC)',
    end_time            DATETIME(6)  NOT NULL COMMENT '封锁结束时间(UTC,半开不含该端点)',
    buffered_start      DATETIME(6)  NOT NULL COMMENT '实际占用起点=开始时间-前置安全缓冲(UTC)',
    buffered_end        DATETIME(6)  NOT NULL COMMENT '实际占用终点=结束时间+后置安全缓冲(UTC,半开不含)',
    pre_buffer_seconds  BIGINT       NOT NULL COMMENT '前置安全缓冲，单位秒',
    post_buffer_seconds BIGINT       NOT NULL COMMENT '后置安全缓冲，单位秒',
    priority            INT          NOT NULL COMMENT '优先级，数值越大越紧急（常规10/紧急20）',
    status              VARCHAR(16)  NOT NULL COMMENT '状态:PENDING/APPROVED/ACTIVE/COMPLETED/CANCELLED/PREEMPTED',
    version             INT          NOT NULL COMMENT '乐观版本号，每次合法迁移加1；初始为1',
    business_time       DATETIME(6)  NOT NULL COMMENT '申请提交业务时间(UTC)',
    created_at          DATETIME(6)  NOT NULL COMMENT '服务端落库时间(UTC)',
    CONSTRAINT pk_possession_request PRIMARY KEY (id),
    CONSTRAINT ck_window_half_open CHECK (end_time > start_time),
    CONSTRAINT ck_buffer_nonnegative CHECK (pre_buffer_seconds >= 0 AND post_buffer_seconds >= 0)
) COMMENT='铁路检修封锁窗申请';

-- 区段维度表：并发审批时对区段行加行锁，无共同区段的申请可并行
CREATE TABLE IF NOT EXISTS rail_section (
    id VARCHAR(64) NOT NULL COMMENT '线路区段ID，全线路唯一',
    CONSTRAINT pk_rail_section PRIMARY KEY (id)
) COMMENT='铁路线路区段';

-- 申请-区段关联表
CREATE TABLE IF NOT EXISTS possession_section (
    request_id VARCHAR(64) NOT NULL COMMENT '封锁申请ID',
    section_id VARCHAR(64) NOT NULL COMMENT '线路区段ID',
    ordinal    INT          NOT NULL COMMENT '申请提交时的区段顺序，从0开始',
    CONSTRAINT pk_possession_section PRIMARY KEY (request_id, section_id),
    CONSTRAINT fk_ps_request FOREIGN KEY (request_id) REFERENCES possession_request (id)
) COMMENT='封锁申请与区段的关联';
CREATE INDEX IF NOT EXISTS idx_ps_section ON possession_section (section_id);

-- 不可变领域事件表（仅追加）
CREATE TABLE IF NOT EXISTS possession_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件自增ID',
    request_id    VARCHAR(64) NOT NULL COMMENT '封锁申请ID',
    event_type    VARCHAR(16) NOT NULL COMMENT '事件:SUBMITTED/APPROVED/ACTIVATED/COMPLETED/CANCELLED/PREEMPTED',
    from_status   VARCHAR(16) COMMENT '迁移前状态，可空（如SUBMITTED）',
    to_status     VARCHAR(16) NOT NULL COMMENT '迁移后状态',
    business_time DATETIME(6) NOT NULL COMMENT '请求携带的业务时间(UTC)',
    version_after INT         NOT NULL COMMENT '事件落定后的申请版本号',
    detail        VARCHAR(1024) COMMENT '事件附加信息（如抢占的申请ID列表）',
    created_at    DATETIME(6) NOT NULL COMMENT '服务端落库时间(UTC)'
) COMMENT='封锁申请状态事件流（仅追加）';
CREATE INDEX IF NOT EXISTS idx_pe_request ON possession_event (request_id, id);

-- 操作幂等账本：同一(requestId,operation)的首次成功结果在此复用
CREATE TABLE IF NOT EXISTS operation_ledger (
    request_id    VARCHAR(64) NOT NULL COMMENT '封锁申请ID（请求键）',
    operation     VARCHAR(16) NOT NULL COMMENT '操作:APPROVE/ACTIVATE/COMPLETE/CANCEL',
    fingerprint   VARCHAR(128) NOT NULL COMMENT '请求指纹:SHA-256(规范化参数)',
    response_json CLOB        NOT NULL COMMENT '首次成功响应的JSON快照',
    created_at    DATETIME(6) NOT NULL COMMENT '首次成功落账时间(UTC)',
    CONSTRAINT pk_operation_ledger PRIMARY KEY (request_id, operation)
) COMMENT='状态操作幂等账本';

-- 不可变计划快照
CREATE TABLE IF NOT EXISTS plan_snapshot (
    id            VARCHAR(64) NOT NULL COMMENT '快照ID',
    business_time DATETIME(6) NOT NULL COMMENT '发布快照请求携带的业务时间(UTC)',
    content_json  CLOB        NOT NULL COMMENT '发布时刻全部申请计划的完整JSON，发布后不再改写',
    created_at    DATETIME(6) NOT NULL COMMENT '服务端发布时间(UTC)',
    CONSTRAINT pk_plan_snapshot PRIMARY KEY (id)
) COMMENT='不可变计划快照';
