-- 频谱租约协调服务建表脚本（H2 MODE=MySQL 与 MySQL 均兼容）
-- 时间列统一存 UTC；频率单位 MHz（半开区间 [low, high)）；EIRP 单位 mW。

-- 幂等请求记录：request_id 为幂等键，指纹覆盖区域、时间、频段、模式、功率与版本
CREATE TABLE IF NOT EXISTS lease_request (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64)  NOT NULL COMMENT '幂等键，由客户端生成并在重试时保持不变',
    request_kind        VARCHAR(16)  NOT NULL COMMENT '请求类型：CREATE/MODIFY/CANCEL',
    lease_id            VARCHAR(64)  COMMENT '请求关联的租约标识；创建成功前为空',
    fingerprint_sha256  CHAR(64)     NOT NULL COMMENT '请求参数指纹（SHA-256 十六进制）',
    http_status         INT          NOT NULL COMMENT '首次请求落定的 HTTP 状态码',
    response_body       CLOB         COMMENT '首次请求落定的响应体（JSON）',
    created_at_utc      TIMESTAMP(3) NOT NULL COMMENT '首次请求落定的 UTC 时刻',
    CONSTRAINT uq_lease_request_request_id UNIQUE (request_id)
);

-- 租约主表：每个租约一行，指向当前版本
CREATE TABLE IF NOT EXISTS lease (
    id                VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '租约唯一标识（服务端生成）',
    current_version   INT          NOT NULL COMMENT '当前生效版本号，从 1 起单调递增',
    status            VARCHAR(16)  NOT NULL COMMENT '租约状态：ACTIVE 生效中 / CANCELLED 已取消',
    created_at_utc    TIMESTAMP(3) NOT NULL COMMENT '创建时刻（UTC）',
    updated_at_utc    TIMESTAMP(3) NOT NULL COMMENT '最近一次版本变更时刻（UTC）'
);

-- 租约版本表：修改追加新版本，历史版本保留不删除
CREATE TABLE IF NOT EXISTS lease_version (
    lease_id        VARCHAR(64)  NOT NULL COMMENT '租约标识',
    version         INT          NOT NULL COMMENT '版本号，从 1 起递增',
    start_time_utc  TIMESTAMP(3) NOT NULL COMMENT '租约生效开始时刻（UTC，含）',
    end_time_utc    TIMESTAMP(3) NOT NULL COMMENT '租约生效结束时刻（UTC，不含）',
    freq_low_mhz    DECIMAL(12,3) NOT NULL COMMENT '频率下限 MHz（含）',
    freq_high_mhz   DECIMAL(12,3) NOT NULL COMMENT '频率上限 MHz（不含）',
    lease_mode      VARCHAR(16)  NOT NULL COMMENT 'EXCLUSIVE 独占 / SHARED 共享',
    eirp_mw         DECIMAL(15,3) NOT NULL COMMENT '等效全向辐射功率，单位 mW，恒为正数',
    status          VARCHAR(16)  NOT NULL COMMENT '该版本所属租约当前状态',
    created_at_utc  TIMESTAMP(3) NOT NULL COMMENT '该版本落定时刻（UTC）',
    cancelled_at_utc TIMESTAMP(3) COMMENT '取消生效时刻（UTC）；NULL 表示未取消，取消只改变该时刻之后的有效性',
    CONSTRAINT pk_lease_version PRIMARY KEY (lease_id, version)
);
CREATE INDEX IF NOT EXISTS idx_lease_version_box
    ON lease_version (start_time_utc, end_time_utc, freq_low_mhz, freq_high_mhz);

-- 租约区域单元表：一个版本对应一个非空区域单元集合
CREATE TABLE IF NOT EXISTS lease_region (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    lease_id     VARCHAR(64) NOT NULL COMMENT '租约标识',
    version      INT         NOT NULL COMMENT '租约版本号',
    region_cell  VARCHAR(64) NOT NULL COMMENT '区域单元标识（合成数据）',
    CONSTRAINT uq_lease_region_triple UNIQUE (lease_id, version, region_cell)
);
CREATE INDEX IF NOT EXISTS idx_lease_region_cell ON lease_region (region_cell);

-- 租约事件流：创建/修改/取消追加事件；幂等重放与终态取消不得新增事件
CREATE TABLE IF NOT EXISTS lease_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    lease_id       VARCHAR(64)  NOT NULL COMMENT '租约标识',
    version        INT          NOT NULL COMMENT '事件发生时的版本号',
    event_type     VARCHAR(24)  NOT NULL COMMENT '事件类型：CREATED/MODIFIED/CANCELLED',
    detail         VARCHAR(512) COMMENT '事件补充说明，可为空',
    created_at_utc TIMESTAMP(3) NOT NULL COMMENT '事件发生时刻（UTC）'
);
CREATE INDEX IF NOT EXISTS idx_lease_event_lease ON lease_event (lease_id, id);

-- 租约写事务串行锁：所有创建/修改/取消事务在该行上 SELECT ... FOR UPDATE，
-- 保证三维冲突检查与共享功率汇总基于可串行化的活动租约集合。
CREATE TABLE IF NOT EXISTS lease_coordination_lock (
    id INT NOT NULL PRIMARY KEY COMMENT '单行协调锁，恒为 1'
);
INSERT INTO lease_coordination_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM lease_coordination_lock WHERE id = 1);
