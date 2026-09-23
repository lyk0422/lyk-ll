-- 铁路检修封锁窗服务建表脚本（H2 MySQL 兼容模式；所有时刻为 UTC epoch 毫秒）。
-- 嵌入式库默认自动执行；使用外部 MySQL 时不由本脚本初始化。

-- 区段注册表：区段以 sectionId 唯一；审批时按区段行加锁，
-- 使共用区段的审批串行化，而无共同区段的申请可并行审批。
CREATE TABLE IF NOT EXISTS section_registry (
    section_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '线路区段唯一标识',
    created_at BIGINT      NOT NULL COMMENT '首次登记时刻,UTC epoch毫秒'
);

CREATE TABLE IF NOT EXISTS possession_request (
    request_id       VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '申请唯一标识',
    start_time       BIGINT       NOT NULL COMMENT '半开时间窗开始(含),UTC epoch毫秒',
    end_time         BIGINT       NOT NULL COMMENT '半开时间窗结束(不含),UTC epoch毫秒',
    buffer_before_ms BIGINT       NOT NULL COMMENT '前安全缓冲,单位毫秒,非负',
    buffer_after_ms  BIGINT       NOT NULL COMMENT '后安全缓冲,单位毫秒,非负',
    priority         INT          NOT NULL COMMENT '优先级,数值越大越紧急',
    version          INT          NOT NULL COMMENT '乐观版本号,每次合法状态迁移递增',
    status           VARCHAR(16)  NOT NULL COMMENT '状态:PENDING/APPROVED/ACTIVE/COMPLETED/CANCELLED/PREEMPTED',
    created_at       BIGINT       NOT NULL COMMENT '创建时刻,UTC epoch毫秒',
    updated_at       BIGINT       NOT NULL COMMENT '最近状态更新时刻,UTC epoch毫秒'
);

CREATE TABLE IF NOT EXISTS request_section (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64)  NOT NULL COMMENT '所属申请',
    section_id VARCHAR(64)  NOT NULL COMMENT '线路区段唯一标识',
    ordinal    INT          NOT NULL COMMENT '区段在申请中的提交顺序,从0开始',
    CONSTRAINT uq_request_section UNIQUE (request_id, section_id)
);
CREATE INDEX IF NOT EXISTS idx_section_lookup ON request_section (section_id, request_id);

CREATE TABLE IF NOT EXISTS request_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(64)  NOT NULL COMMENT '所属申请',
    event_type    VARCHAR(24)  NOT NULL COMMENT '事件:SUBMITTED/APPROVED/ACTIVATED/COMPLETED/CANCELLED/PREEMPTED',
    from_status   VARCHAR(16) COMMENT '迁移前状态,首个事件为NULL',
    to_status     VARCHAR(16)  NOT NULL COMMENT '迁移后状态',
    business_time BIGINT COMMENT '客户端携带的业务时间,UTC epoch毫秒,可空',
    occurred_at   BIGINT       NOT NULL COMMENT '服务端记录时刻,UTC epoch毫秒',
    fingerprint   VARCHAR(64) COMMENT '操作指纹,幂等重放不追加事件',
    detail        CLOB COMMENT '附加明细JSON,可空'
);
CREATE INDEX IF NOT EXISTS idx_event_request ON request_event (request_id, id);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_key   VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '客户端幂等请求键',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT '覆盖requestId/expectedVersion/业务时间等参数的SHA-256指纹',
    operation     VARCHAR(16)  NOT NULL COMMENT '操作类型:ACTIVATE/COMPLETE/CANCEL',
    request_id    VARCHAR(64)  NOT NULL COMMENT '关联申请',
    response_code INT          NOT NULL COMMENT '首次响应HTTP状态码',
    response_body CLOB COMMENT '首次响应体JSON',
    created_at    BIGINT       NOT NULL COMMENT '首次记录时刻,UTC epoch毫秒'
);

CREATE TABLE IF NOT EXISTS plan_snapshot (
    snapshot_id       VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '快照唯一标识',
    request_id        VARCHAR(64) NOT NULL COMMENT '所属申请',
    version_at_publish INT        NOT NULL COMMENT '发布时的申请版本',
    status_at_publish VARCHAR(16) NOT NULL COMMENT '发布时的申请状态',
    content           CLOB        NOT NULL COMMENT '不可变快照内容JSON,后续状态变化不得改写',
    published_at      BIGINT      NOT NULL COMMENT '发布时刻,UTC epoch毫秒'
);
