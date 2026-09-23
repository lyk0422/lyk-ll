-- 本地频道播出编排服务建表脚本（H2 MODE=MySQL 兼容；仅 JVM 生命周期内的内存数据）
-- 所有时间列均为 UTC，DATETIME(3) 毫秒精度；半开时段统一约定为 [start_time, end_time)。

CREATE TABLE IF NOT EXISTS channel (
    channel            VARCHAR(128)  NOT NULL,
    current_version_id BIGINT NULL COMMENT '当前绑定播单版本ID；NULL表示尚未发布任何版本',
    watermark          DATETIME(3) NULL COMMENT '结算水位线(UTC)：最近一次tick时间，早于该时刻的插播开始被拒绝',
    created_at         DATETIME(3) NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    CONSTRAINT pk_channel PRIMARY KEY (channel)
);

CREATE TABLE IF NOT EXISTS playlist_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel         VARCHAR(128) NOT NULL COMMENT '频道标识',
    version_label   VARCHAR(128) NOT NULL COMMENT '调用方提供的播单版本号；同频道唯一',
    published_at    DATETIME(3) NOT NULL COMMENT '发布时间(UTC)',
    CONSTRAINT ux_version_channel_label UNIQUE (channel, version_label)
);

CREATE TABLE IF NOT EXISTS playlist_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id      BIGINT NOT NULL COMMENT '所属播单版本ID',
    item_id         VARCHAR(128) NOT NULL COMMENT '节目项唯一标识（同版本内唯一）',
    material_digest VARCHAR(512) NOT NULL COMMENT '素材摘要（非空）',
    start_time      DATETIME(3) NOT NULL COMMENT '节目开始时间(UTC)，半开区间左端',
    end_time        DATETIME(3) NOT NULL COMMENT '节目结束时间(UTC)，半开区间右端，端点相接合法',
    seq_no          INT NOT NULL COMMENT '版本内按开始时间排序的序号',
    CONSTRAINT ux_item_version_item UNIQUE (version_id, item_id)
);

CREATE TABLE IF NOT EXISTS live_insert (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel         VARCHAR(128) NOT NULL COMMENT '频道标识',
    request_id      VARCHAR(128) NOT NULL COMMENT '直播插播请求幂等键；同频道唯一',
    priority        INT NOT NULL COMMENT '插播优先级，整数越大优先级越高；播单素材基线为0',
    start_time      DATETIME(3) NOT NULL COMMENT '插播开始时间(UTC)',
    end_time        DATETIME(3) NULL COMMENT '插播结束时间(UTC)；NULL表示进行中',
    end_kind        VARCHAR(16) NULL COMMENT '结束类型：ENDED-正常结束，PREEMPTED-被更高优先级插播抢占',
    fingerprint     VARCHAR(64) NOT NULL COMMENT '开始请求参数指纹(start+priority)',
    first_sequence  BIGINT NULL COMMENT '该插播首条LIVE播出记录的频道内序号',
    created_at      DATETIME(3) NOT NULL,
    CONSTRAINT ux_live_channel_request UNIQUE (channel, request_id)
);

CREATE TABLE IF NOT EXISTS as_run (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel          VARCHAR(128) NOT NULL COMMENT '频道标识',
    channel_seq      BIGINT NOT NULL COMMENT '频道内单调递增的asRunSequence',
    version_id       BIGINT NULL COMMENT '生成该记录时绑定的播单版本ID；LIVE记录可能为NULL',
    kind             VARCHAR(8) NOT NULL COMMENT '记录类型：ITEM-播单素材，LIVE-直播插播',
    item_id          VARCHAR(128) NULL COMMENT '播单节目项ID；kind=ITEM时非空',
    material_digest  VARCHAR(512) NULL COMMENT '素材摘要；kind=ITEM时非空',
    live_request_id  VARCHAR(128) NULL COMMENT '直播插播requestId；kind=LIVE时非空',
    priority         INT NULL COMMENT 'LIVE记录的插播优先级；ITEM记录为NULL',
    start_time       DATETIME(3) NOT NULL COMMENT '本段实际播出开始时间(UTC)',
    end_time         DATETIME(3) NULL COMMENT '本段实际播出结束时间(UTC)；NULL表示尚未结束(开放段)',
    ack_state        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '适配器ACK状态：PENDING-待确认，ACKED-已确认；被后续记录取代后迟到ACK不得改写',
    created_at       DATETIME(3) NOT NULL,
    CONSTRAINT ux_as_run_channel_seq UNIQUE (channel, channel_seq)
);

CREATE TABLE IF NOT EXISTS request_fingerprint (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel          VARCHAR(128) NOT NULL COMMENT '频道标识',
    request_key      VARCHAR(160) NOT NULL COMMENT '幂等键：插播为requestId，ACK为ackKey',
    action           VARCHAR(16) NOT NULL COMMENT '动作类型：LIVE_START/LIVE_END/ACK',
    fingerprint      VARCHAR(64) NOT NULL COMMENT '请求参数指纹；同键异参冲突',
    status_code      INT NOT NULL COMMENT '首次冻结结果HTTP状态码',
    frozen_response  CLOB NOT NULL COMMENT '首次冻结响应体JSON',
    created_at       DATETIME(3) NOT NULL,
    CONSTRAINT ux_fp_channel_key_action UNIQUE (channel, request_key, action)
);
