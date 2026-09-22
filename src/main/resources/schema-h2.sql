-- rosbag 风格时序消息存储的 H2（MODE=MySQL）建表脚本。
-- 仅使用 H2 兼容语法，数据随 JVM 生命周期保留（内存库）。

-- 逻辑分段：每个 Topic 独立编号，维护边界、消息数、状态与段级校验值
CREATE TABLE IF NOT EXISTS rb_segment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分段主键',
    topic            VARCHAR(255) NOT NULL COMMENT '主题名',
    segment_no       BIGINT       NOT NULL COMMENT 'Topic 内从 0 开始的分段序号',
    start_ns         BIGINT       NULL COMMENT '段内最小消息时间戳（纳秒，UTC 纪元）',
    end_ns           BIGINT       NULL COMMENT '段内最大消息时间戳（纳秒，UTC 纪元）',
    start_seq        BIGINT       NULL COMMENT '段内最小 Topic 序列号',
    end_seq          BIGINT       NULL COMMENT '段内最大 Topic 序列号',
    message_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '已确认完整的消息条数',
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '分段状态：OPEN 可写 / SEALED 已封段 / RECOVERED 经恢复重建',
    segment_checksum BIGINT       NOT NULL DEFAULT 0 COMMENT '段级 CRC32 校验值（无符号 32 位存入 BIGINT）',
    CONSTRAINT uk_segment_topic_no UNIQUE (topic, segment_no)
);
COMMENT ON TABLE rb_segment IS 'rosbag 风格逻辑分段元数据与段级校验';

-- 消息主体
CREATE TABLE IF NOT EXISTS rb_message (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息主键',
    topic            VARCHAR(255) NOT NULL COMMENT '主题名',
    seq              BIGINT       NOT NULL COMMENT 'Topic 内单调递增序列号，不得倒退',
    timestamp_ns     BIGINT       NOT NULL COMMENT '消息时间戳（纳秒，UTC 纪元）',
    type_id          VARCHAR(255) NOT NULL COMMENT '消息类型标识',
    payload          CLOB         NOT NULL COMMENT '消息负载原文（UTF-8 文本）',
    payload_checksum BIGINT       NOT NULL COMMENT '负载 CRC32 校验值（无符号 32 位存入 BIGINT）',
    batch_no         VARCHAR(255) NOT NULL COMMENT '写入批次号，用于同参重试幂等',
    segment_id       BIGINT       NOT NULL COMMENT '所属逻辑分段主键',
    valid            BOOLEAN      NOT NULL DEFAULT TRUE COMMENT 'TRUE=完整可查；FALSE=恢复时隔离的不完整后缀消息',
    CONSTRAINT uk_message_topic_seq UNIQUE (topic, seq),
    CONSTRAINT uk_message_topic_batch UNIQUE (topic, batch_no)
);
COMMENT ON TABLE rb_message IS 'rosbag 风格时序消息主体';

-- 查询索引表：恢复时可整体删除并按消息重建；查询必须先定位分段再走该索引
CREATE TABLE IF NOT EXISTS rb_message_index (
    message_id   BIGINT      NOT NULL COMMENT '指向 rb_message.id',
    topic        VARCHAR(255) NOT NULL COMMENT '主题名（冗余以加速 Topic 过滤）',
    segment_id   BIGINT      NOT NULL COMMENT '所属分段主键',
    timestamp_ns BIGINT      NOT NULL COMMENT '消息时间戳（纳秒）',
    seq          BIGINT      NOT NULL COMMENT 'Topic 内序列号',
    CONSTRAINT pk_message_index PRIMARY KEY (message_id)
);
COMMENT ON TABLE rb_message_index IS '消息的时间/序列组合查询索引，可在恢复时重建';

CREATE INDEX IF NOT EXISTS idx_index_lookup
    ON rb_message_index (topic, segment_id, timestamp_ns, seq);

-- 恢复时被隔离的不完整后缀消息：从 rb_message 整体搬移至此表，
-- 既保留现场可供核查，又释放原 seq/批次号供调用方重试写入。
CREATE TABLE IF NOT EXISTS rb_message_quarantine (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '隔离记录主键',
    message_id       BIGINT       NOT NULL COMMENT '原 rb_message.id',
    topic            VARCHAR(255) NOT NULL COMMENT '主题名',
    seq              BIGINT       NOT NULL COMMENT '原 Topic 内序列号',
    timestamp_ns     BIGINT       NOT NULL COMMENT '原消息时间戳（纳秒）',
    type_id          VARCHAR(255) NOT NULL COMMENT '原消息类型标识',
    payload          CLOB         NOT NULL COMMENT '原消息负载原文',
    payload_checksum BIGINT       NOT NULL COMMENT '原负载 CRC32 校验值',
    batch_no         VARCHAR(255) NOT NULL COMMENT '原写入批次号',
    segment_id       BIGINT       NOT NULL COMMENT '原所属分段主键',
    quarantined_at_ns BIGINT      NOT NULL COMMENT '隔离发生时间（纳秒，UTC 纪元）',
    reason           VARCHAR(255) NOT NULL COMMENT '隔离原因，如校验失败/序列号不连续/段外多余消息'
);
COMMENT ON TABLE rb_message_quarantine IS '恢复时隔离的不完整/损坏消息现场';

-- 回放记录：重复请求（相同 replay_key）不得重复创建
CREATE TABLE IF NOT EXISTS rb_replay (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '回放记录主键',
    replay_key    VARCHAR(255) NOT NULL COMMENT '调用方提供的回放幂等键',
    topic         VARCHAR(255) NOT NULL COMMENT '回放主题',
    from_ns       BIGINT       NULL COMMENT '回放起始时间戳（纳秒），NULL 表示不限',
    to_ns         BIGINT       NULL COMMENT '回放结束时间戳（纳秒），NULL 表示不限',
    message_count BIGINT       NOT NULL COMMENT '本次回放命中的消息条数',
    created_at    BIGINT       NOT NULL COMMENT '记录创建时间（纳秒，UTC 纪元）',
    CONSTRAINT uk_replay_key UNIQUE (replay_key)
);
COMMENT ON TABLE rb_replay IS '按原顺序回放的幂等记录';
