-- rosbag 风格时序存储建表脚本（H2 MODE=MySQL，兼容标准 MySQL 类型子集）

-- Topic 串行锁：每个 Topic 一行，写入事务先取该行行锁，保证分段滚动与序列号分配串行
CREATE TABLE IF NOT EXISTS bag_topic_lock (
    topic VARCHAR(255) NOT NULL PRIMARY KEY COMMENT 'Topic 名称（锁行）'
);
COMMENT ON TABLE bag_topic_lock IS 'Topic 级写入串行锁';

-- 逻辑分段：维护 Topic 内分段序号、纳秒/序列号边界、消息数、状态与段级校验
CREATE TABLE IF NOT EXISTS bag_segment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分段主键',
    topic           VARCHAR(255)  NOT NULL COMMENT 'Topic 名称',
    seg_index       INT           NOT NULL COMMENT 'Topic 内从 0 开始的分段序号',
    start_ns        BIGINT        NULL COMMENT '段内最小消息时间戳（纳秒，UTC 纪元），空段为空',
    end_ns          BIGINT        NULL COMMENT '段内最大消息时间戳（纳秒，UTC 纪元），空段为空',
    start_seq       BIGINT        NULL COMMENT '段内最小 Topic 内序列号，空段为空',
    end_seq         BIGINT        NULL COMMENT '段内最大 Topic 内序列号，空段为空',
    message_count   BIGINT        NOT NULL DEFAULT 0 COMMENT '段内已提交消息数',
    max_size        INT           NOT NULL COMMENT '该分段允许的最大消息数',
    status          VARCHAR(16)   NOT NULL COMMENT '分段状态：OPEN 可写 / SEALED 已封段 / DAMAGED 写入中断待恢复',
    checksum        VARCHAR(64)   NULL COMMENT '段级链式 SHA-256 校验值（十六进制），封段或恢复后写入',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
    CONSTRAINT uk_bag_segment_topic_idx UNIQUE (topic, seg_index)
);
COMMENT ON TABLE bag_segment IS 'rosbag 风格逻辑分段及粗粒度索引';

-- 消息主体
CREATE TABLE IF NOT EXISTS bag_message (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息主键',
    topic             VARCHAR(255) NOT NULL COMMENT 'Topic 名称',
    timestamp_ns      BIGINT       NOT NULL COMMENT '消息纳秒时间戳（UTC 纪元纳秒）',
    seq               BIGINT       NOT NULL COMMENT 'Topic 内单调递增序列号，不得倒退',
    type_id           VARCHAR(255) NOT NULL COMMENT '消息类型标识',
    payload           BLOB         NULL COMMENT '消息负载二进制内容',
    payload_checksum  VARCHAR(64)  NOT NULL COMMENT '负载 SHA-256 校验值（十六进制）',
    chain_value       VARCHAR(64)  NULL COMMENT '段内链式校验锚点：截至本条消息的链值；中断尾消息为空，恢复时据此定位完整前缀',
    batch_no          VARCHAR(128) NOT NULL COMMENT '写入批次号，同批次同参重试幂等',
    segment_id        BIGINT       NOT NULL COMMENT '所属逻辑分段 id',
    status            VARCHAR(16)  NOT NULL DEFAULT 'COMMITTED' COMMENT '消息状态：COMMITTED 已提交 / QUARANTINED 恢复时隔离的不完整消息',
    written_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    CONSTRAINT uk_bag_message_topic_seq UNIQUE (topic, seq),
    CONSTRAINT uk_bag_message_batch UNIQUE (batch_no, topic, seq),
    CONSTRAINT fk_bag_message_segment FOREIGN KEY (segment_id) REFERENCES bag_segment (id)
);
COMMENT ON TABLE bag_message IS 'rosbag 风格消息主体';

-- 查询索引：分段定位后通过该索引按时间/序列范围取消息，恢复时可独立重建
CREATE TABLE IF NOT EXISTS bag_msg_index (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '索引主键',
    message_id    BIGINT       NOT NULL COMMENT '对应 bag_message.id',
    topic         VARCHAR(255) NOT NULL COMMENT 'Topic 名称（冗余便于索引下推）',
    timestamp_ns  BIGINT       NOT NULL COMMENT '消息纳秒时间戳',
    seq           BIGINT       NOT NULL COMMENT 'Topic 内序列号',
    segment_id    BIGINT       NOT NULL COMMENT '所属逻辑分段 id',
    CONSTRAINT uk_bag_msg_index_message UNIQUE (message_id)
);
CREATE INDEX IF NOT EXISTS idx_bag_msg_index_topic_ns  ON bag_msg_index (topic, timestamp_ns);
CREATE INDEX IF NOT EXISTS idx_bag_msg_index_topic_seq ON bag_msg_index (topic, seq);
COMMENT ON TABLE bag_msg_index IS '消息二级索引，支持 Topic+时间范围+序列范围组合查询，可由恢复操作重建';

-- 恢复时隔离出的不完整消息：在同一事务内从 bag_message 移出，保留现场并释放 Topic 序列号占用
CREATE TABLE IF NOT EXISTS bag_quarantine (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '隔离记录主键',
    original_message_id BIGINT     NOT NULL COMMENT '原 bag_message.id',
    topic             VARCHAR(255) NOT NULL COMMENT 'Topic 名称',
    timestamp_ns      BIGINT       NOT NULL COMMENT '消息纳秒时间戳',
    seq               BIGINT       NOT NULL COMMENT 'Topic 内序列号',
    type_id           VARCHAR(255) NOT NULL COMMENT '消息类型标识',
    payload           BLOB         NULL COMMENT '消息负载二进制内容',
    payload_checksum  VARCHAR(64)  NOT NULL COMMENT '负载 SHA-256 校验值（十六进制）',
    chain_value       VARCHAR(64)  NULL COMMENT '段内链式校验锚点（原样保留）；中断尾消息为空',
    batch_no          VARCHAR(128) NOT NULL COMMENT '原始写入批次号',
    segment_id        BIGINT       NOT NULL COMMENT '原所属逻辑分段 id',
    reason            VARCHAR(64)  NOT NULL COMMENT '隔离原因：INCOMPLETE_TAIL 未链入段校验的中断尾消息',
    quarantined_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '隔离时间'
);
COMMENT ON TABLE bag_quarantine IS '恢复操作隔离的不完整消息现场';

-- 回放记录：同一回放键重复请求不重复创建
CREATE TABLE IF NOT EXISTS bag_play (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '回放记录主键',
    play_key       VARCHAR(128) NOT NULL COMMENT '回放幂等键，相同键重复请求只创建一条记录',
    topic          VARCHAR(255) NOT NULL COMMENT '回放 Topic',
    start_ns       BIGINT       NULL COMMENT '回放起始纳秒时间戳，空表示不限',
    end_ns         BIGINT       NULL COMMENT '回放结束纳秒时间戳，空表示不限',
    start_seq      BIGINT       NULL COMMENT '回放起始序列号，空表示不限',
    end_seq        BIGINT       NULL COMMENT '回放结束序列号，空表示不限',
    message_count  BIGINT       NOT NULL COMMENT '本次回放命中的消息数',
    replay_checksum VARCHAR(64) NULL COMMENT '回放消息序列校验值（十六进制），用于重复回放一致性校验',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次创建时间',
    CONSTRAINT uk_bag_play_key UNIQUE (play_key)
);
COMMENT ON TABLE bag_play IS '回放请求记录，重复请求复用同一记录';

-- 回放消息快照：首次回放时按原顺序保存命中消息的完整副本，
-- 重复回放严格复现该序列，不受后续写入或恢复隔离影响
CREATE TABLE IF NOT EXISTS bag_play_item (
    play_id          BIGINT       NOT NULL COMMENT '所属 bag_play.id',
    position         INT          NOT NULL COMMENT '回放顺序位，从 0 开始',
    message_id       BIGINT       NOT NULL COMMENT '命中时的 bag_message.id',
    topic            VARCHAR(255) NOT NULL COMMENT '消息 Topic 快照',
    timestamp_ns     BIGINT       NOT NULL COMMENT '消息纳秒时间戳快照',
    seq              BIGINT       NOT NULL COMMENT 'Topic 内序列号快照',
    type_id          VARCHAR(255) NOT NULL COMMENT '消息类型标识快照',
    payload          BLOB         NULL COMMENT '消息负载快照',
    payload_checksum VARCHAR(64)  NOT NULL COMMENT '负载校验值快照',
    batch_no         VARCHAR(128) NOT NULL COMMENT '写入批次号快照',
    segment_id       BIGINT       NOT NULL COMMENT '命中时所属分段 id',
    CONSTRAINT pk_bag_play_item PRIMARY KEY (play_id, position)
);
COMMENT ON TABLE bag_play_item IS '回放消息序列完整快照，保证重复回放顺序与内容一致';
