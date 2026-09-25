-- 污水排放在线监测（CEMS）本地 H2（MODE=MySQL）建表脚本，仅使用合成数据。
-- 时间统一为 Asia/Shanghai 时区下的时刻；限值/读数单位为 mg/L，总量基数为小时均值按小时累计的合成代理量。

CREATE TABLE IF NOT EXISTS outfall (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    outfall_code        VARCHAR(64)  NOT NULL COMMENT '排口编码',
    outfall_name        VARCHAR(128) COMMENT '排口名称',
    status              VARCHAR(32)  NOT NULL COMMENT '排口状态：REGISTERED 登记/MONITORING 监测中/MARKED 已超标标记/RESTRICTED 限排中/ARCHIVED 已归档(终态)',
    daily_limit         DECIMAL(14,4) COMMENT '日均许可限值 mg/L，NULL 表示未登记限值',
    hourly_limit        DECIMAL(14,4) COMMENT '小时均值许可限值 mg/L，NULL 表示未登记限值',
    total_baseline      DECIMAL(18,4) COMMENT '限排生效时冻结的排放总量基数(小时均值累计代理量)，NULL 表示尚未冻结',
    baseline_frozen_at  DATETIME COMMENT '总量基数冻结时刻，NULL 表示未冻结',
    registered_at       DATETIME NOT NULL COMMENT '登记时刻',
    updated_at          DATETIME NOT NULL COMMENT '最近状态变更时刻'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_outfall_code ON outfall(outfall_code);

CREATE TABLE IF NOT EXISTS monitor_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    outfall_code  VARCHAR(64) NOT NULL COMMENT '排口编码',
    period_type   VARCHAR(8)  NOT NULL COMMENT '时段类型：HOUR 小时/DAY 自然日',
    period_start  DATETIME    NOT NULL COMMENT '时段起始时刻(整点/零点)',
    avg_value     DECIMAL(14,4) NOT NULL COMMENT '时段平均浓度 mg/L',
    is_late       TINYINT     NOT NULL DEFAULT 0 COMMENT '是否归档后迟到数据：1 是(仅记迟到事件不入本表) 0 否',
    request_id    VARCHAR(64) COMMENT '写入请求的 requestId',
    created_at    DATETIME    NOT NULL COMMENT '服务接收时刻'
);
-- 监测数据按“排口＋时段”去重
CREATE UNIQUE INDEX IF NOT EXISTS uk_reading_slot
    ON monitor_reading(outfall_code, period_type, period_start);
CREATE INDEX IF NOT EXISTS idx_reading_outfall
    ON monitor_reading(outfall_code, period_type, period_start);

CREATE TABLE IF NOT EXISTS exceedance_mark (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    outfall_code  VARCHAR(64) NOT NULL COMMENT '排口编码',
    request_id    VARCHAR(64) NOT NULL COMMENT '生成标记的 requestId',
    day_start     DATETIME    NOT NULL COMMENT '被标记自然日零点',
    daily_avg     DECIMAL(14,4) NOT NULL COMMENT '当日日均值 mg/L',
    limit_value   DECIMAL(14,4) NOT NULL COMMENT '判定时适用的日均限值 mg/L',
    status        VARCHAR(16) NOT NULL COMMENT '标记状态：ACTIVE 有效/RESOLVED 复测合格解除',
    created_at    DATETIME NOT NULL COMMENT '标记生成时刻'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mark_request ON exceedance_mark(request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mark_day ON exceedance_mark(outfall_code, day_start);

CREATE TABLE IF NOT EXISTS restriction_order (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    outfall_code        VARCHAR(64)  NOT NULL COMMENT '排口编码',
    request_id          VARCHAR(64)  NOT NULL COMMENT '下达限排令的 requestId(自动触发时由触发请求派生)',
    active_slot         VARCHAR(64) COMMENT '执行中限排令占位列：执行中=排口编码，关闭后=NULL，配合唯一索引保证同排口同时刻仅一个执行中限排令',
    trigger_slots       VARCHAR(255) NOT NULL COMMENT '触发限排的连续3个超标小时时段，逗号分隔',
    status              VARCHAR(16)  NOT NULL COMMENT '限排令状态：ISSUED 已下达/ESCALATED 已升级/CLOSED 已关闭',
    issued_at           DATETIME NOT NULL COMMENT '下达时刻',
    confirm_deadline    DATETIME NOT NULL COMMENT '确认执行截止时刻(下达后2小时)',
    confirmed           TINYINT NOT NULL DEFAULT 0 COMMENT '是否已确认执行：1 是 0 否',
    confirmed_at        DATETIME COMMENT '确认执行时刻，NULL 未确认',
    escalated           TINYINT NOT NULL DEFAULT 0 COMMENT '是否已超时升级：1 是 0 否',
    escalated_at        DATETIME COMMENT '升级时刻，NULL 未升级',
    production_limited  TINYINT NOT NULL DEFAULT 0 COMMENT '是否已联动限产：1 是 0 否',
    baseline_total      DECIMAL(18,4) NOT NULL COMMENT '下达同事务冻结的排放总量基数',
    baseline_frozen_at  DATETIME NOT NULL COMMENT '基数冻结时刻(=下达时刻)',
    retest_slot         DATETIME COMMENT '复测小时时段，NULL 未复测',
    retest_value        DECIMAL(14,4) COMMENT '复测小时均值 mg/L，NULL 未复测',
    release_request_id  VARCHAR(64) COMMENT '解除请求 requestId，NULL 未解除',
    released_at         DATETIME COMMENT '解除/关闭时刻，NULL 未解除',
    created_at          DATETIME NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_request ON restriction_order(request_id);
-- 关键并发约束：同一排口同时刻只允许一个执行中限排令（NULL 不参与唯一）
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_active ON restriction_order(active_slot);

CREATE TABLE IF NOT EXISTS missing_notice (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    outfall_code  VARCHAR(64) NOT NULL COMMENT '排口编码',
    gap_start     DATETIME NOT NULL COMMENT '缺失区间起点(首个缺失小时整点)',
    gap_end       DATETIME NOT NULL COMMENT '缺失区间终点(末个缺失小时整点)',
    missing_hours INT NOT NULL COMMENT '连续缺失小时数；超过2小时才督办，恰2小时不生成',
    status        VARCHAR(16) NOT NULL COMMENT '督办状态：OPEN 督办中/CLEARED 已清除',
    created_at    DATETIME NOT NULL COMMENT '督办生成时刻'
);
CREATE INDEX IF NOT EXISTS idx_notice_outfall ON missing_notice(outfall_code, status);

CREATE TABLE IF NOT EXISTS idempotent_event (
    request_id    VARCHAR(64) PRIMARY KEY COMMENT '幂等键 requestId',
    action        VARCHAR(24) NOT NULL COMMENT '动作：REGISTER/HOURLY_READING/DAILY_READING/RELEASE/ARCHIVE',
    outfall_code  VARCHAR(64) COMMENT '关联排口编码',
    fingerprint   CHAR(64) NOT NULL COMMENT 'SHA-256 指纹，覆盖 requestId 与全部业务字段',
    request_json  CLOB NOT NULL COMMENT '首次请求业务字段规范化 JSON',
    result_json   CLOB NOT NULL COMMENT '首次响应 JSON，重放原样返回',
    created_at    DATETIME NOT NULL COMMENT '首次受理时刻'
);

CREATE TABLE IF NOT EXISTS outfall_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    outfall_code  VARCHAR(64) NOT NULL COMMENT '排口编码',
    event_type    VARCHAR(24) NOT NULL COMMENT '事件类型：LATE 归档后迟到/MISSING 数据缺失督办/RESTRICT 限排/RELEASE 解除/ARCHIVE 归档',
    detail        VARCHAR(512) COMMENT '事件说明',
    created_at    DATETIME NOT NULL COMMENT '事件记录时刻'
);
CREATE INDEX IF NOT EXISTS idx_outfall_event ON outfall_event(outfall_code, id);
