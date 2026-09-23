-- 频谱租约协调服务建表脚本（H2 MySQL 兼容模式；数据仅存在于 JVM 生命周期内）。

CREATE TABLE IF NOT EXISTS lease (
    lease_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    current_version INT NOT NULL COMMENT '当前生效版本号，从 1 开始',
    status          VARCHAR(16) NOT NULL COMMENT '租约状态：ACTIVE=活动，CANCELLED=已取消（取消只改变未来有效性）',
    create_time     BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒，UTC',
    update_time     BIGINT NOT NULL COMMENT '最近变更时间，epoch 毫秒，UTC',
    cancel_time     BIGINT NULL COMMENT '取消生效时间，epoch 毫秒 UTC；NULL 表示未取消',
    CONSTRAINT chk_lease_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

CREATE TABLE IF NOT EXISTS lease_version (
    lease_id     BIGINT NOT NULL,
    version      INT NOT NULL COMMENT '版本号，从 1 递增，历史版本永久保留',
    start_time   BIGINT NOT NULL COMMENT 'UTC 生效开始时刻（含），epoch 毫秒',
    end_time     BIGINT NOT NULL COMMENT 'UTC 生效结束时刻（不含），epoch 毫秒，半开区间',
    freq_low_mhz DOUBLE NOT NULL COMMENT '频率下界 MHz（含），半开区间',
    freq_high_mhz DOUBLE NOT NULL COMMENT '频率上界 MHz（不含），半开区间',
    mode         VARCHAR(16) NOT NULL COMMENT 'EXCLUSIVE=独占，SHARED=共享',
    eirp_mw      DOUBLE NOT NULL COMMENT '等效全向辐射功率，单位 mW（线性毫瓦），必须为正数',
    request_id   VARCHAR(128) NOT NULL COMMENT '产生该版本的请求幂等键',
    created_at   BIGINT NOT NULL COMMENT '版本写入时间，epoch 毫秒 UTC',
    CONSTRAINT pk_lease_version PRIMARY KEY (lease_id, version),
    CONSTRAINT chk_version_mode CHECK (mode IN ('EXCLUSIVE', 'SHARED')),
    CONSTRAINT chk_version_eirp CHECK (eirp_mw > 0)
);

CREATE TABLE IF NOT EXISTS lease_cell (
    lease_id BIGINT NOT NULL,
    version  INT NOT NULL COMMENT '所属租约版本',
    cell     VARCHAR(64) NOT NULL COMMENT '区域单元标识（合成数据），同一版本内唯一',
    CONSTRAINT pk_lease_cell PRIMARY KEY (lease_id, version, cell)
);

CREATE TABLE IF NOT EXISTS lease_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    lease_id   BIGINT NOT NULL,
    version    INT NOT NULL COMMENT '事件对应的租约版本',
    request_id VARCHAR(128) NULL COMMENT '触发事件的请求幂等键；取消可能为空',
    type       VARCHAR(16) NOT NULL COMMENT 'CREATED=创建，MODIFIED=修改，CANCELLED=取消',
    event_time BIGINT NOT NULL COMMENT '事件时间，epoch 毫秒 UTC',
    CONSTRAINT chk_event_type CHECK (type IN ('CREATED', 'MODIFIED', 'CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_lease_event_lease ON lease_event (lease_id, version);

-- 请求幂等指纹记录：同键同参返回首次结果，同键异参冲突。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id      VARCHAR(128) PRIMARY KEY COMMENT '请求幂等键',
    fingerprint     VARCHAR(128) NOT NULL COMMENT '请求指纹（区域、时间、频段、模式、功率、租约与版本）的 SHA-256',
    http_status     INT NOT NULL COMMENT '首次响应 HTTP 状态码',
    result_lease_id BIGINT NULL COMMENT '首次成功结果对应的租约 ID；失败结果为 NULL',
    error_code      VARCHAR(48) NULL COMMENT '首次业务失败的稳定错误码；成功为 NULL',
    error_body      CLOB NULL COMMENT '首次业务失败响应体（冲突解释报告 JSON）；成功为 NULL',
    created_at      BIGINT NOT NULL COMMENT '首次请求时间，epoch 毫秒 UTC'
);

-- 写事务串行化协调锁：所有创建/修改/取消事务先对该行加行级写锁，
-- 保证“先检查后写入”的共享功率聚合判断不会被并发事务同时越过。
CREATE TABLE IF NOT EXISTS coord_lock (
    id TINYINT PRIMARY KEY
);
MERGE INTO coord_lock (id) KEY(id) VALUES (1);
