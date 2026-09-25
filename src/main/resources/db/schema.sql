-- 充电漫游本地服务建表脚本（H2 内存库，MODE=MySQL；数据仅在 JVM 生命周期内保留）

-- 运营商
CREATE TABLE IF NOT EXISTS operator (
    id              BIGINT       NOT NULL PRIMARY KEY,
    operator_code   VARCHAR(32)  NOT NULL COMMENT '运营商编码',
    operator_name   VARCHAR(128) NOT NULL COMMENT '运营商名称',
    tariff_receivable DECIMAL(10,4) NOT NULL COMMENT '应收电价（元/kWh，车主持属运营商向车主收取单价）',
    tariff_payable    DECIMAL(10,4) NOT NULL COMMENT '应付电价（元/kWh，向服务运营商结算单价）',
    CONSTRAINT uk_operator_code UNIQUE (operator_code)
);

-- 车辆（以证书指纹标识）
CREATE TABLE IF NOT EXISTS vehicle (
    id               BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    cert_fingerprint VARCHAR(128) NOT NULL COMMENT '车辆证书指纹（SHA-256 十六进制或合成指纹）',
    home_operator_id BIGINT       NOT NULL COMMENT '车主持属运营商ID',
    enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许授权：1允许 0停用',
    CONSTRAINT uk_vehicle_fingerprint UNIQUE (cert_fingerprint)
);

-- 充电桩
CREATE TABLE IF NOT EXISTS charging_pile (
    id          BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    pile_no     VARCHAR(64)  NOT NULL COMMENT '桩编号',
    operator_id BIGINT      NOT NULL COMMENT '桩所属（服务）运营商ID',
    power_kw    DECIMAL(8,2) NOT NULL COMMENT '额定功率（kW）',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否在用：1在用 0停用',
    CONSTRAINT uk_pile_no UNIQUE (pile_no)
);

-- 充电会话
CREATE TABLE IF NOT EXISTS charging_session (
    id                  BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    session_no          VARCHAR(64)  NOT NULL COMMENT '会话号（业务唯一）',
    cert_fingerprint    VARCHAR(128) NOT NULL COMMENT '车辆证书指纹',
    pile_no             VARCHAR(64)  NOT NULL COMMENT '桩编号',
    home_operator_id    BIGINT       NOT NULL COMMENT '车辆归属运营商ID',
    serving_operator_id BIGINT       NOT NULL COMMENT '服务（桩）运营商ID',
    status              VARCHAR(16)  NOT NULL COMMENT '会话状态：AUTHORIZED已授权 CHARGING充电中 ENDED已结束',
    start_time_utc      DATETIME(3)  NOT NULL COMMENT '授权/开始时刻（UTC）',
    end_time_utc        DATETIME(3)  NULL COMMENT '结束时刻（UTC），未结束为空',
    settlement_day      DATE         NULL COMMENT '清分归属日（UTC日，按结束时刻）',
    total_energy_kwh    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '结束时累计电量（kWh）',
    accounted_seq       INT          NOT NULL DEFAULT -1 COMMENT '已封存计入清分的最大抄表序号，-1表示未封存',
    batch_id            BIGINT       NULL COMMENT '所属清分批ID，非空即唯一归属一个批次',
    created_at_utc      DATETIME(3)  NOT NULL COMMENT '创建时刻（UTC）',
    updated_at_utc      DATETIME(3)  NOT NULL COMMENT '最近更新时刻（UTC）',
    CONSTRAINT uk_session_no UNIQUE (session_no),
    CONSTRAINT uk_session_fingerprint_time UNIQUE (cert_fingerprint, start_time_utc)
);
CREATE INDEX IF NOT EXISTS idx_session_batch ON charging_session (batch_id);
CREATE INDEX IF NOT EXISTS idx_session_day ON charging_session (settlement_day, status);

-- 计量快照（按会话号+抄表序号去重，乱序按序号归位）
CREATE TABLE IF NOT EXISTS meter_snapshot (
    id             BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    session_no     VARCHAR(64)   NOT NULL COMMENT '会话号',
    reading_seq    INT           NOT NULL COMMENT '抄表序号（从0递增，乱序可后到）',
    energy_kwh     DECIMAL(12,3) NOT NULL COMMENT '该序号电表累计读数（kWh）',
    recorded_at_utc DATETIME(3) NOT NULL COMMENT '桩端计量时刻（UTC）',
    request_id     VARCHAR(64)   NOT NULL COMMENT '上传请求requestId',
    created_at_utc DATETIME(3)   NOT NULL COMMENT '入库时刻（UTC）',
    CONSTRAINT uk_meter_session_seq UNIQUE (session_no, reading_seq)
);

-- requestId 幂等登记表（指纹覆盖 requestId 与全部业务字段）
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '业务请求requestId',
    request_type  VARCHAR(24)  NOT NULL COMMENT '请求类型：AUTHORIZE/START/STOP/METER/SEAL/WRITE_OFF',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT '业务字段SHA-256指纹',
    http_status   INT          NOT NULL COMMENT '首次响应HTTP状态码',
    response_body CLOB         NULL COMMENT '首次响应JSON原文',
    created_at_utc DATETIME(3) NOT NULL COMMENT '首次请求时刻（UTC）'
);

-- 清分日锁（串行化“会话结束”与“批次封存”，保证会话唯一归属）
CREATE TABLE IF NOT EXISTS settlement_day_lock (
    settlement_day DATE   NOT NULL PRIMARY KEY COMMENT '清分日（UTC）',
    sealed         TINYINT NOT NULL DEFAULT 0 COMMENT '是否已封存：0否 1是',
    batch_id       BIGINT NULL COMMENT '封存后对应的清分批ID'
);

-- 清分批
CREATE TABLE IF NOT EXISTS settlement_batch (
    id               BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    batch_no         VARCHAR(64)   NOT NULL COMMENT '清分单号',
    settlement_day   DATE          NOT NULL COMMENT '清分日（UTC）',
    status           VARCHAR(16)   NOT NULL COMMENT '状态：SEALED已封存（终态）',
    session_count    INT           NOT NULL COMMENT '批内会话笔数',
    total_energy_kwh DECIMAL(12,3) NOT NULL COMMENT '批内合计电量（kWh），必须等于明细之和',
    total_receivable DECIMAL(14,2) NOT NULL COMMENT '批内合计应收（元），必须等于明细之和',
    total_payable    DECIMAL(14,2) NOT NULL COMMENT '批内合计应付（元），必须等于明细之和',
    sealed_at_utc    DATETIME(3)   NOT NULL COMMENT '封存时刻（UTC）',
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT uk_batch_day UNIQUE (settlement_day)
);

-- 清分明细
CREATE TABLE IF NOT EXISTS settlement_detail (
    id                  BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    batch_id            BIGINT        NOT NULL COMMENT '清分批ID',
    session_id          BIGINT        NOT NULL COMMENT '充电会话ID',
    session_no          VARCHAR(64)   NOT NULL COMMENT '会话号',
    home_operator_id    BIGINT        NOT NULL COMMENT '车辆归属运营商ID',
    serving_operator_id BIGINT        NOT NULL COMMENT '服务运营商ID',
    energy_kwh          DECIMAL(12,3) NOT NULL COMMENT '计入本批的电量（kWh）',
    receivable_amount   DECIMAL(14,2) NOT NULL COMMENT '本笔应收（元）',
    payable_amount      DECIMAL(14,2) NOT NULL COMMENT '本笔应付（元）',
    CONSTRAINT uk_detail_session UNIQUE (session_id)
);

-- 差异挂账（封存后迟到计量形成，须人工核销，留痕）
CREATE TABLE IF NOT EXISTS suspense_account (
    id               BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    suspense_no      VARCHAR(64)   NOT NULL COMMENT '挂账单号',
    session_id       BIGINT        NOT NULL COMMENT '充电会话ID',
    session_no       VARCHAR(64)   NOT NULL COMMENT '会话号',
    batch_id         BIGINT        NULL COMMENT '关联已封存清分批ID（无批次时为空）',
    reading_seq      INT           NOT NULL COMMENT '迟到快照抄表序号',
    energy_delta_kwh DECIMAL(12,3) NOT NULL COMMENT '差异电量（kWh，相对已封存读数的增量）',
    receivable_delta DECIMAL(14,2) NOT NULL COMMENT '差异应收（元）',
    payable_delta    DECIMAL(14,2) NOT NULL COMMENT '差异应付（元）',
    reason           VARCHAR(255)  NOT NULL COMMENT '挂账原因',
    status           VARCHAR(16)   NOT NULL COMMENT '状态：PENDING待核销 WRITTEN_OFF已核销',
    created_at_utc   DATETIME(3)   NOT NULL COMMENT '挂账时刻（UTC）',
    CONSTRAINT uk_suspense_no UNIQUE (suspense_no),
    CONSTRAINT uk_suspense_session_seq UNIQUE (session_id, reading_seq)
);

-- 挂账核销留痕（同一挂账只允许核销一次）
CREATE TABLE IF NOT EXISTS suspense_write_off (
    id              BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    suspense_id     BIGINT       NOT NULL COMMENT '挂账ID',
    request_id      VARCHAR(64)  NOT NULL COMMENT '核销请求requestId',
    operator_name   VARCHAR(64)  NOT NULL COMMENT '核销操作人',
    remark          VARCHAR(255) NULL COMMENT '核销备注',
    written_off_at_utc DATETIME(3) NOT NULL COMMENT '核销时刻（UTC）',
    CONSTRAINT uk_writeoff_suspense UNIQUE (suspense_id)
);
