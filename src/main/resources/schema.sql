-- CAN DBC 增量解析服务建表脚本（H2 MODE=MySQL 兼容语法）
-- 所有表仅在当前 JVM 生命周期内保留数据（嵌入式内存库）。

-- DBC 版本：一次原子导入生成一个可用版本
CREATE TABLE IF NOT EXISTS dbc_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '版本主键',
    version_name    VARCHAR(128) NOT NULL COMMENT 'DBC 版本名称，如 v1',
    request_id      VARCHAR(128) NOT NULL COMMENT '导入请求幂等键 requestId',
    payload_digest  VARCHAR(64)  NOT NULL COMMENT '导入请求体规范化摘要（SHA-256 十六进制）',
    created_at_nano BIGINT       NOT NULL COMMENT '导入时间（纳秒，JVM 内单调时钟）',
    PRIMARY KEY (id),
    UNIQUE (version_name),
    UNIQUE (request_id)
);

-- 报文定义：版本 + CAN ID 唯一
CREATE TABLE IF NOT EXISTS dbc_message_def (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '报文定义主键',
    version_id   BIGINT      NOT NULL COMMENT '所属 DBC 版本主键',
    can_id       BIGINT      NOT NULL COMMENT 'CAN 帧 ID（标准/扩展帧标识值，无单位）',
    message_name VARCHAR(128) NOT NULL COMMENT '报文名称',
    dlc          INT         NOT NULL COMMENT '期望负载长度 DLC（字节）',
    PRIMARY KEY (id),
    UNIQUE (version_id, can_id)
);

-- 信号定义
CREATE TABLE IF NOT EXISTS dbc_signal_def (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '信号定义主键',
    message_id      BIGINT       NOT NULL COMMENT '所属报文定义主键',
    signal_name     VARCHAR(128) NOT NULL COMMENT '信号名称',
    byte_order      VARCHAR(16)  NOT NULL COMMENT '字节序：BIG_ENDIAN（Motorola）或 LITTLE_ENDIAN（Intel）',
    start_bit       INT          NOT NULL COMMENT '起始位（DBC 起始位编号，0..63）',
    bit_length      INT          NOT NULL COMMENT '信号位长度（1..64）',
    "SIGNED"        BOOLEAN      NOT NULL COMMENT '是否带符号位',
    factor          DOUBLE       NOT NULL COMMENT '缩放系数（物理值=原始值*factor+offset）',
    offset_value    DOUBLE       NOT NULL COMMENT '偏移量',
    unit            VARCHAR(64)  NOT NULL COMMENT '工程单位，空字符串表示无单位',
    multiplex_role  VARCHAR(16)  NOT NULL COMMENT '多路复用角色：NONE 普通信号 / MULTIPLEXOR 多路选择器 / MULTIPLEXED 被选择信号',
    multiplex_value BIGINT       NULL COMMENT '被选择信号要求的选择器原始值；角色非 MULTIPLEXED 时为空',
    PRIMARY KEY (id),
    UNIQUE (message_id, signal_name)
);

-- 信号枚举值（原始值 -> 文本）
CREATE TABLE IF NOT EXISTS dbc_signal_enum (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '枚举项主键',
    signal_id    BIGINT       NOT NULL COMMENT '所属信号定义主键',
    enum_value   BIGINT       NOT NULL COMMENT '枚举原始值',
    enum_text    VARCHAR(128) NOT NULL COMMENT '枚举文本，如 On/Off',
    PRIMARY KEY (id),
    UNIQUE (signal_id, enum_value)
);

-- 帧审计：重复、延迟、乱序帧均留存
CREATE TABLE IF NOT EXISTS frame_audit (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审计记录主键',
    version_id      BIGINT       NOT NULL COMMENT '解析时使用的 DBC 版本主键',
    can_id          BIGINT       NOT NULL COMMENT 'CAN 帧 ID',
    frame_seq       BIGINT       NOT NULL COMMENT '帧序号（调用方提供，单调递增）',
    timestamp_ns    BIGINT       NOT NULL COMMENT '帧纳秒时戳（调用方提供）',
    payload         VARBINARY(64) NOT NULL COMMENT '原始负载字节',
    accepted        BOOLEAN      NOT NULL COMMENT '是否为接受（更新状态）的帧；false 表示被拒绝或旧帧',
    reject_reason   VARCHAR(32)  NULL COMMENT '拒绝原因：BAD_LENGTH/UNKNOWN_ID/MUX_NOT_MATCH/STALE_FRAME；接受帧（含相同帧重放）为空',
    created_at_nano BIGINT       NOT NULL COMMENT '服务接收时间（纳秒）',
    PRIMARY KEY (id)
);

-- 信号最新状态：按版本 + CAN ID + 信号 保存最新有效帧
CREATE TABLE IF NOT EXISTS signal_state (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '信号状态主键',
    version_id      BIGINT       NOT NULL COMMENT 'DBC 版本主键',
    can_id          BIGINT       NOT NULL COMMENT 'CAN 帧 ID',
    signal_name     VARCHAR(128) NOT NULL COMMENT '信号名称',
    raw_value       BIGINT       NOT NULL COMMENT '最近一次解码原始值',
    physical_value  DOUBLE       NOT NULL COMMENT '最近一次解码物理值（含缩放与偏移）',
    enum_text       VARCHAR(128) NULL COMMENT '最近一次枚举文本，无枚举映射时为空',
    frame_seq       BIGINT       NOT NULL COMMENT '产生该状态的帧序号',
    timestamp_ns    BIGINT       NOT NULL COMMENT '产生该状态的帧纳秒时戳',
    PRIMARY KEY (id),
    UNIQUE (version_id, can_id, signal_name)
);

-- 状态变更事件：相同帧重放不得重复产生
CREATE TABLE IF NOT EXISTS state_change_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '事件主键',
    version_id      BIGINT       NOT NULL COMMENT 'DBC 版本主键',
    can_id          BIGINT       NOT NULL COMMENT 'CAN 帧 ID',
    frame_seq       BIGINT       NOT NULL COMMENT '产生事件的帧序号',
    timestamp_ns    BIGINT       NOT NULL COMMENT '产生事件的帧纳秒时戳',
    signal_name     VARCHAR(128) NOT NULL COMMENT '发生变更的信号名称',
    old_raw_value   BIGINT       NULL COMMENT '变更前原始值；首次出现时为空',
    new_raw_value   BIGINT       NOT NULL COMMENT '变更后原始值',
    created_at_nano BIGINT       NOT NULL COMMENT '事件产生时间（纳秒）',
    PRIMARY KEY (id),
    UNIQUE (version_id, can_id, frame_seq, signal_name)
);
