-- CAN DBC 增量解析服务建表脚本（H2 MODE=MySQL；数据仅保留在 JVM 生命周期内）

-- DBC 导入幂等请求记录：requestId 唯一；content_hash 为规范化定义哈希，用于同参/异参重放判定
CREATE TABLE IF NOT EXISTS dbc_import_requests (
    request_id    VARCHAR(128) NOT NULL COMMENT '导入请求幂等键，同键重放不重复写入',
    version       VARCHAR(128) NOT NULL COMMENT '本次导入的 DBC 版本名',
    content_hash  VARCHAR(128) NOT NULL COMMENT '规范化定义内容 SHA-256（不含 requestId），异参重放据此判定',
    outcome       VARCHAR(32)  NOT NULL COMMENT '导入结果：SUCCESS',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dbc_import_requests PRIMARY KEY (request_id)
);

-- DBC 版本：一行代表一个整体生效的版本
CREATE TABLE IF NOT EXISTS dbc_versions (
    version     VARCHAR(128) NOT NULL COMMENT 'DBC 版本名，帧解析按版本独立选择定义',
    request_id  VARCHAR(128) NOT NULL COMMENT '首次成功导入该版本的请求 ID',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dbc_versions PRIMARY KEY (version)
);

-- 报文定义：版本 + CAN ID 唯一
CREATE TABLE IF NOT EXISTS dbc_messages (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    version VARCHAR(128) NOT NULL,
    can_id  BIGINT       NOT NULL COMMENT 'CAN 标识符，范围 0..0x1FFFFFFF（11/29 位）',
    name    VARCHAR(128) NOT NULL,
    dlc     INT          NOT NULL COMMENT '固定负载长度，单位字节，经典 CAN 范围 0..8',
    CONSTRAINT pk_dbc_messages PRIMARY KEY (id),
    CONSTRAINT uk_dbc_messages UNIQUE (version, can_id)
);

-- 信号定义
CREATE TABLE IF NOT EXISTS dbc_signals (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    message_id  BIGINT       NOT NULL,
    name        VARCHAR(128) NOT NULL,
    start_bit   INT          NOT NULL COMMENT 'DBC 起始位号：Intel 为 LSB，Motorola 为 sawtooth 起点(MSB)',
    bit_length  INT          NOT NULL COMMENT '信号位长，单位位，范围 1..64',
    endian      VARCHAR(8)   NOT NULL COMMENT '字节序：LITTLE=Intel(@1)，BIG=Motorola(@0)',
    is_signed   BOOLEAN      NOT NULL COMMENT '是否有符号（true 时按补码解释）',
    factor      DOUBLE       NOT NULL COMMENT '缩放系数，物理值=原始值*factor+offset，无量纲',
    offset_value DOUBLE      NOT NULL COMMENT '偏移量',
    mux_mode    VARCHAR(16)  NOT NULL COMMENT '多路复用角色：NONE/MULTIPLEXOR(选择信号 M)/MUXED(被复用 mN)',
    mux_value   BIGINT       NULL COMMENT 'MUXED 信号要求的选择信号值；其余角色为 NULL',
    CONSTRAINT pk_dbc_signals PRIMARY KEY (id),
    CONSTRAINT uk_dbc_signals UNIQUE (message_id, name),
    CONSTRAINT fk_dbc_signals_message FOREIGN KEY (message_id) REFERENCES dbc_messages (id)
);

-- 信号枚举值
CREATE TABLE IF NOT EXISTS dbc_signal_enums (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    signal_id  BIGINT       NOT NULL,
    raw_value  BIGINT       NOT NULL COMMENT '枚举对应的信号原始整数值',
    label      VARCHAR(128) NOT NULL,
    CONSTRAINT pk_dbc_signal_enums PRIMARY KEY (id),
    CONSTRAINT uk_dbc_signal_enums UNIQUE (signal_id, raw_value),
    CONSTRAINT fk_dbc_enums_signal FOREIGN KEY (signal_id) REFERENCES dbc_signals (id)
);

-- 帧审计：所有收到的帧（含拒绝、重放）均留存；版本+CAN ID+序号唯一用于去重
CREATE TABLE IF NOT EXISTS frame_audit (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    version       VARCHAR(128) NOT NULL,
    can_id        BIGINT       NOT NULL,
    timestamp_ns  BIGINT       NOT NULL COMMENT '帧纳秒时戳，用于新旧判定',
    seq           BIGINT       NOT NULL COMMENT '帧序号，时戳相同时按序号判定新旧；同序号视为同一帧',
    payload       BLOB         NOT NULL COMMENT '帧原始负载字节',
    accepted      BOOLEAN      NOT NULL COMMENT '是否成功解码（非法长度/未知ID/复用不匹配为 false）',
    reason        VARCHAR(32)  NULL COMMENT '拒绝原因：INVALID_LENGTH/UNKNOWN_ID/MUX_NOT_MATCHED；接受时为 NULL',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_frame_audit PRIMARY KEY (id),
    CONSTRAINT uk_frame_audit UNIQUE (version, can_id, seq)
);

-- 信号最新有效状态：按 版本+CAN ID+信号 独立保存最新一帧
CREATE TABLE IF NOT EXISTS signal_state (
    version        VARCHAR(128) NOT NULL,
    can_id         BIGINT       NOT NULL,
    signal_name    VARCHAR(128) NOT NULL,
    raw_value      BIGINT       NOT NULL COMMENT '当前状态信号原始值（有符号已补码解释）',
    physical_value DOUBLE       NOT NULL COMMENT '当前状态物理值=raw*factor+offset',
    enum_label     VARCHAR(128) NULL COMMENT '枚举文本；无枚举或未命中为 NULL',
    timestamp_ns   BIGINT       NOT NULL COMMENT '产生当前状态的帧纳秒时戳',
    seq            BIGINT       NOT NULL COMMENT '产生当前状态的帧序号',
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_signal_state PRIMARY KEY (version, can_id, signal_name)
);

-- 状态变更事件：仅在帧更新某信号状态时产生，相同帧重放不重复产生
CREATE TABLE IF NOT EXISTS signal_state_events (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    version      VARCHAR(128) NOT NULL,
    can_id       BIGINT       NOT NULL,
    signal_name  VARCHAR(128) NOT NULL,
    seq          BIGINT       NOT NULL,
    timestamp_ns BIGINT       NOT NULL,
    old_raw      BIGINT       NULL COMMENT '变更前原始值；该信号首次出现时为 NULL',
    new_raw      BIGINT       NOT NULL COMMENT '变更后原始值',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_signal_state_events PRIMARY KEY (id)
);
