-- 本地频道播出编排服务建表脚本。
-- 兼容 H2（MODE=MySQL）与 MySQL；时间统一为 UTC 毫秒时间戳（BIGINT），空值含义见列注释。

CREATE TABLE IF NOT EXISTS playlist_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '播单版本主键',
    channel_id VARCHAR(64) NOT NULL COMMENT '频道标识',
    version_no INT NOT NULL COMMENT '频道内唯一的播单版本号',
    item_count INT NOT NULL COMMENT '该版本包含的节目项数量',
    published_at_ms BIGINT NOT NULL COMMENT '发布时间，UTC 毫秒时间戳',
    CONSTRAINT uk_playlist_version_channel UNIQUE (channel_id, version_no)
);

CREATE TABLE IF NOT EXISTS playlist_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '节目项主键',
    version_id BIGINT NOT NULL COMMENT '所属播单版本主键',
    channel_id VARCHAR(64) NOT NULL COMMENT '频道标识（冗余，便于查询）',
    item_id VARCHAR(64) NOT NULL COMMENT '节目项标识，版本内唯一',
    media_digest VARCHAR(128) NOT NULL COMMENT '素材摘要',
    start_ms BIGINT NOT NULL COMMENT '播出开始，UTC 毫秒，半开区间起点（含）',
    end_ms BIGINT NOT NULL COMMENT '播出结束，UTC 毫秒，半开区间终点（不含）',
    position INT NOT NULL COMMENT '按开始时间升序排列的序号，从 0 开始',
    CONSTRAINT uk_playlist_item_version UNIQUE (version_id, item_id)
);

CREATE TABLE IF NOT EXISTS channel_state (
    channel_id VARCHAR(64) PRIMARY KEY COMMENT '频道标识',
    current_version_id BIGINT NULL COMMENT '当前绑定的播单版本主键；未发布时为空',
    watermark_ms BIGINT NOT NULL DEFAULT 0 COMMENT '结算水位线，UTC 毫秒；结束时间不晚于它的 as-run 记录不可修改',
    next_seq BIGINT NOT NULL DEFAULT 1 COMMENT '下一个 as-run 序号，频道内单调递增、不复用',
    last_tick_ms BIGINT NULL COMMENT '最近一次调度 tick 时间，UTC 毫秒；未 tick 时为空'
);

CREATE TABLE IF NOT EXISTS as_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'as-run 记录主键',
    channel_id VARCHAR(64) NOT NULL COMMENT '频道标识',
    seq BIGINT NOT NULL COMMENT '播出序号，频道内单调递增',
    kind VARCHAR(16) NOT NULL COMMENT '记录类型：SCHEDULED 排播 / BREAK 直播插播',
    item_id VARCHAR(64) NULL COMMENT '节目项标识；插播记录为空',
    media_digest VARCHAR(128) NOT NULL COMMENT '实际播出素材摘要',
    break_request_id VARCHAR(64) NULL COMMENT '插播请求标识；排播记录为空',
    start_ms BIGINT NOT NULL COMMENT '实际播出开始，UTC 毫秒，半开区间起点（含）',
    end_ms BIGINT NULL COMMENT '实际播出结束，UTC 毫秒，半开区间终点（不含）；空表示在播',
    status VARCHAR(16) NOT NULL COMMENT 'OPEN 在播 / CLOSED 已结束 / SUPERSEDED 被后续记录取代',
    acked TINYINT NOT NULL DEFAULT 0 COMMENT '适配器 ACK 标志：0 未确认，1 已确认',
    acked_at_ms BIGINT NULL COMMENT 'ACK 时间，UTC 毫秒；未确认为空',
    CONSTRAINT uk_as_run_channel_seq UNIQUE (channel_id, seq)
);

CREATE TABLE IF NOT EXISTS break_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '插播请求主键',
    channel_id VARCHAR(64) NOT NULL COMMENT '频道标识',
    request_id VARCHAR(64) NOT NULL COMMENT '插播请求标识，频道内唯一，作为幂等键',
    priority INT NOT NULL COMMENT '插播优先级，数值越大越高',
    start_ms BIGINT NOT NULL COMMENT '插播开始，UTC 毫秒',
    end_ms BIGINT NULL COMMENT '插播结束，UTC 毫秒；进行中为空',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE 进行中 / ENDED 已结束',
    fingerprint VARCHAR(128) NOT NULL COMMENT '开始请求参数指纹，用于同键异参检测',
    start_response CLOB NOT NULL COMMENT '首次开始请求的冻结响应 JSON，幂等重放原样返回',
    end_response CLOB NULL COMMENT '首次结束请求的冻结响应 JSON；未结束为空',
    CONSTRAINT uk_break_request_channel UNIQUE (channel_id, request_id)
);
